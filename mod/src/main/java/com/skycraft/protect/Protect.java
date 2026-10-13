package com.skycraft.protect;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.dig.DiggingRules;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.homestead.HomesteadClaim;
import com.skycraft.homestead.HomesteadData;
import com.skycraft.survival.Harvesting;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world protection decision ("Skyrim: you can't pick up the world"), server side.
 *
 * <p><b>Breaking</b>, outside any homestead claim:</p>
 * <ol>
 *     <li>plants of every kind (wild flowers, mushrooms, crops, grass, vines...) and blocks the player placed: allowed;</li>
 *     <li>ore and mineral veins: allowed, except in a settlement or on a road;</li>
 *     <li>trees (logs, leaves): allowed away from any settlement, road, structure or landmark;</li>
 *     <li>natural terrain (dirt, sand, stone...): never inside a footprint; elsewhere by the configured mode;</li>
 *     <li>everything else (glass, beds, doors, chests, furniture, walls, torches, signs...): never.</li>
 * </ol>
 * <p>Inside a claim the player may build in, everything is allowed (the homestead and dig rules still apply);
 * inside a claim they may not build in, nothing is. <b>Placing</b> outside a claim is limited to the blocks of
 * {@code #skycraft:placeable_anywhere} (camp gear, the drafting table that founds a homestead).</p>
 *
 * <p>Taking, moving and using things (items on the ground, containers, doors) is not touched here at all.</p>
 */
public final class Protect {
    /** Blocks a player may place anywhere: camping gear and the drafting table that founds a homestead. */
    public static final TagKey<Block> PLACEABLE_ANYWHERE =
            TagKey.create(Registries.BLOCK, new ResourceLocation(Skycraft.MODID, "placeable_anywhere"));

    /** An answer and why (a short code, also shown by {@code /skycraft-protect check}). */
    public record Decision(boolean allowed, String reason) {
        static Decision ok(String reason) {
            return new Decision(true, reason);
        }

        static Decision no(String reason) {
            return new Decision(false, reason);
        }
    }

    private static final Set<UUID> BYPASS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST_HINT = new HashMap<>();

    private Protect() {}

    // ------------------------------------------------------------------ switches

    public static boolean enabled() {
        return ProtectConfig.ENABLED.get();
    }

    /** True for players the rules do not apply to: creative mode (config), spectators, ops who toggled bypass. */
    public static boolean bypassed(@Nullable Player player) {
        if (player == null) return false;
        if (player.isSpectator()) return true;
        if (ProtectConfig.CREATIVE_BYPASS.get() && player.isCreative()) return true;
        return BYPASS.contains(player.getUUID());
    }

    /** Toggles the op bypass for a player; returns the new state. */
    public static boolean toggleBypass(UUID player) {
        if (BYPASS.remove(player)) return false;
        BYPASS.add(player);
        return true;
    }

    public static boolean hasBypass(UUID player) {
        return BYPASS.contains(player);
    }

    static void forget(UUID player) {
        BYPASS.remove(player);
        synchronized (LAST_HINT) {
            LAST_HINT.remove(player);
        }
    }

    static void clearAll() {
        BYPASS.clear();
        synchronized (LAST_HINT) {
            LAST_HINT.clear();
        }
        Footprints.clear();
    }

    // ------------------------------------------------------------------ claims

    @Nullable
    public static HomesteadClaim claimAt(ServerLevel level, BlockPos pos) {
        return HomesteadData.get(level.getServer()).getClaimHorizontal(level, pos);
    }

    /** True if the player may build in the claim that covers {@code pos} (false when there is none). */
    public static boolean mayBuildAt(@Nullable Player player, ServerLevel level, BlockPos pos) {
        HomesteadClaim claim = claimAt(level, pos);
        return claim != null && player instanceof ServerPlayer sp && claim.canBuild(sp);
    }

    // ------------------------------------------------------------------ block classes

    /** Plants nobody owns: grass, flowers, mushrooms, crops, vines, cactus, cane, kelp, cobwebs, fire, snow layers. */
    public static boolean isAmbientPlant(BlockState state) {
        Block b = state.getBlock();
        return b instanceof BushBlock || b instanceof GrowingPlantBlock || b instanceof VineBlock || b instanceof MultifaceBlock
                || b instanceof CactusBlock || b instanceof SugarCaneBlock || b instanceof BambooStalkBlock
                || b instanceof HangingRootsBlock || b instanceof WebBlock || b instanceof BaseFireBlock
                || b == Blocks.SNOW || b == Blocks.SPORE_BLOSSOM || b == Blocks.POINTED_DRIPSTONE || b == Blocks.MOSS_CARPET
                || state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS);
    }

    /** Wood and foliage of a tree (also the fungus trees of the Nether). */
    public static boolean isTree(BlockState state) {
        Block b = state.getBlock();
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.WART_BLOCKS)
                || b instanceof HugeMushroomBlock || b == Blocks.SHROOMLIGHT
                || b == Blocks.MANGROVE_ROOTS || b == Blocks.MUDDY_MANGROVE_ROOTS;
    }

    /** Natural ground: the dig module's soft, hard and deep terrain tags. */
    public static boolean isTerrain(BlockState state) {
        return state.is(DiggingRules.SOFT) || state.is(DiggingRules.HARD) || state.is(DiggingRules.DEEP);
    }

    // ------------------------------------------------------------------ the decisions

    /** May {@code player} (null: a stranger) break the block {@code state} at {@code pos}? */
    public static Decision checkBreak(@Nullable Player player, ServerLevel level, BlockPos pos, BlockState state) {
        if (!enabled()) return Decision.ok("disabled");
        if (bypassed(player)) return Decision.ok("bypass");
        HomesteadClaim claim = claimAt(level, pos);
        // wild plants are everyone's to pick, on anyone's land (owner decision, playtest 4); what somebody planted in
        // a homestead they own stays theirs
        boolean plant = Harvesting.isHarvestable(state) || isAmbientPlant(state);
        if (plant && (claim == null || !PlacedBlocks.isPlayerPlaced(level, pos))) return Decision.ok("plant");
        if (claim != null) {
            boolean may = player instanceof ServerPlayer sp && claim.canBuild(sp);
            return may ? Decision.ok("homestead") : Decision.no("foreign_homestead");
        }
        if (plant) return Decision.ok("plant");
        // a worked-out vein is unbreakable by itself; leave its own "the vein is depleted" hint to speak
        if (state.getBlock() instanceof com.skycraft.survival.block.DepletedOreBlock) return Decision.ok("depleted_vein");
        if (PlacedBlocks.isPlayerPlaced(level, pos)) return Decision.ok("own_block");

        boolean ore = state.is(Tags.Blocks.ORES);
        boolean tree = !ore && isTree(state);
        boolean terrain = !ore && !tree && isTerrain(state);
        if (!ore && !tree && !terrain) return Decision.no("protected");

        Footprints.Kind fp = Footprints.at(level, pos);
        if (ore) {
            if (!ProtectConfig.ORE_MINING.get()) return Decision.no("ore_disabled");
            if (fp == Footprints.Kind.SETTLEMENT || fp == Footprints.Kind.ROAD) return Decision.no("ore_in_town");
            return Decision.ok("ore");
        }
        if (tree) {
            if (!ProtectConfig.TREE_CHOPPING.get()) return Decision.no("tree_disabled");
            if (fp.built()) return Decision.no("tree_in_footprint");
            return Decision.ok("tree");
        }
        if (fp.built()) return Decision.no("terrain_footprint");
        String mode = ProtectConfig.terrainMode();
        if ("homestead".equals(mode)) return Decision.no("terrain");
        return Decision.ok("terrain_" + mode);
    }

    /** May {@code player} place {@code placed} at {@code pos}? */
    public static Decision checkPlace(@Nullable Player player, ServerLevel level, BlockPos pos, BlockState placed) {
        if (!enabled()) return Decision.ok("disabled");
        if (bypassed(player)) return Decision.ok("bypass");
        HomesteadClaim claim = claimAt(level, pos);
        if (claim != null) {
            boolean may = player instanceof ServerPlayer sp && claim.canBuild(sp);
            return may ? Decision.ok("homestead") : Decision.no("foreign_homestead");
        }
        if (placed.is(PLACEABLE_ANYWHERE)) return Decision.ok("placeable_anywhere");
        return Decision.no("place");
    }

    // ------------------------------------------------------------------ the hint

    /** The translation key of the red hint that explains a denial. */
    public static String hintKey(Decision d) {
        return switch (d.reason()) {
            case "place" -> "message.skycraft.protect.place";
            case "terrain", "terrain_footprint" -> "message.skycraft.protect.terrain";
            case "tree_in_footprint", "tree_disabled" -> "message.skycraft.protect.tree";
            case "foreign_homestead" -> "message.skycraft.protect.foreign";
            case "fluid" -> "message.skycraft.protect.fluid";
            default -> "message.skycraft.protect.break";
        };
    }

    /** Shows the red "This isn't yours to break" hint, at most once per cooldown per player. */
    public static void hint(ServerPlayer player, Decision d) {
        hint(player, hintKey(d));
    }

    public static void hint(ServerPlayer player, String key) {
        if (player instanceof FakePlayer || player.connection == null) return; // machines and tests are not told
        long now = player.level().getGameTime();
        int cooldown = ProtectConfig.HINT_COOLDOWN_TICKS.get();
        synchronized (LAST_HINT) {
            Long last = LAST_HINT.get(player.getUUID());
            if (last != null && now - last < cooldown && now >= last) return;
            LAST_HINT.put(player.getUUID(), now);
        }
        Notifier.message(player, Component.translatable(key).withStyle(ChatFormatting.RED));
    }
}
