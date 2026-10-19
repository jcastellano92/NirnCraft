package com.nirncraft.survival;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Notifier;
import com.nirncraft.dig.PlacedBlocks;
import com.nirncraft.survival.block.DepletedOreBlock;
import com.nirncraft.survival.block.DepletedOreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.FakePlayer;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Regenerating ore veins. When a player mines a natural (not player-placed) ore ({@code #forge:ores}) in the
 * Overworld or Nether, the hole is filled on the next tick with {@code nirncraft:depleted_ore}, which turns back into
 * the original ore after {@link SurvivalConfig#ORE_REGEN_TICKS}.
 *
 * <p>The replacement waits a tick so the ore's drops, Mining XP and the Vein Miner chain (which breaks each block
 * through {@code destroyBlock} and fires its own break event) all see the real ore.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class OreVeins {
    private record Pending(ResourceKey<Level> dim, BlockPos pos, BlockState ore, int attempts) {
    }

    /** Ores seen at HIGHEST priority (before DiggingRules forgets the placed flag), confirmed at LOWEST. */
    private static final Map<GlobalPos, BlockState> CANDIDATES = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    /** Give up re-trying a hole an entity is standing in after this many ticks. */
    private static final int MAX_ATTEMPTS = 200;
    private static final Map<UUID, Long> LAST_HINT = new HashMap<>();

    private OreVeins() {}

    /** The clock regrowth is measured in: game time, but sleeping/waiting (which moves the day time) counts too. */
    public static long clock(Level level) {
        return Math.max(level.getOverworldClockTime(), level.getGameTime());
    }

    private static boolean applies(Level level) {
        if (!SurvivalConfig.ORE_REGEN.get()) return false;
        if (level.dimension() == Level.OVERWORLD) return true;
        return level.dimension() == Level.NETHER && SurvivalConfig.ORE_REGEN_NETHER.get();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreakFirst(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.isCreative() || player instanceof FakePlayer || !applies(level)) return;
        BlockState state = event.getState();
        if (!state.is(Tags.Blocks.ORES)) return;
        if (PlacedBlocks.isPlayerPlaced(level, event.getPos())) return;
        CANDIDATES.put(GlobalPos.of(level.dimension(), event.getPos().immutable()), state);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreakLast(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if (CANDIDATES.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockState ore = CANDIDATES.remove(GlobalPos.of(level.dimension(), event.getPos()));
        if (ore != null) PENDING.add(new Pending(level.dimension(), event.getPos().immutable(), ore, 0));
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        CANDIDATES.clear(); // breaks that were cancelled after HIGHEST
        if (PENDING.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            PENDING.clear();
            return;
        }
        List<Pending> retry = new ArrayList<>();
        List<Pending> batch = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Pending p : batch) {
            ServerLevel level = server.getLevel(p.dim);
            if (level == null || !level.isLoaded(p.pos)) continue;
            BlockState now = level.getBlockState(p.pos);
            // only fill a real hole (air or flowing liquid), never something built there meanwhile
            if (!now.isAir() && (now.getFluidState().isEmpty() || !now.canBeReplaced())) continue;
            if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(p.pos)).isEmpty()) {
                if (p.attempts < MAX_ATTEMPTS) retry.add(new Pending(p.dim, p.pos, p.ore, p.attempts + 1));
                continue;
            }
            deplete(level, p.pos, p.ore);
        }
        PENDING.addAll(retry);
    }

    /** Tells a player hitting a depleted vein why nothing happens (at most every two seconds). */
    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isCreative()) return;
        if (!event.getLevel().getBlockState(event.getPos()).is(SurvivalRegistry.DEPLETED_ORE.get())) return;
        long now = player.level().getGameTime();
        Long last = LAST_HINT.get(player.getUUID());
        if (last != null && now - last < 40) return;
        LAST_HINT.put(player.getUUID(), now);
        Notifier.message(player, Component.translatable("message.nirncraft.survival.depleted"));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_HINT.remove(event.getEntity().getUUID());
    }

    /** Puts a depleted vein at {@code pos} that grows back into {@code ore}. */
    public static void deplete(ServerLevel level, BlockPos pos, BlockState ore) {
        BlockState depleted = SurvivalRegistry.DEPLETED_ORE.get().defaultBlockState().setValue(DepletedOreBlock.HOST, hostOf(level, ore));
        if (!level.setBlock(pos, depleted, 3)) return;
        if (level.getBlockEntity(pos) instanceof DepletedOreBlockEntity be) {
            long ticks = SurvivalConfig.ORE_REGEN_TICKS.get();
            be.setup(ore, clock(level) + ticks);
            level.scheduleTick(pos, depleted.getBlock(), (int) Math.min(6000, ticks));
        }
    }

    private static DepletedOreBlock.Host hostOf(Level level, BlockState ore) {
        if (ore.is(Tags.Blocks.ORES_IN_GROUND_DEEPSLATE)) return DepletedOreBlock.Host.DEEPSLATE;
        if (ore.is(Tags.Blocks.ORES_IN_GROUND_NETHERRACK)) return DepletedOreBlock.Host.NETHERRACK;
        if (ore.is(Tags.Blocks.ORES_IN_GROUND_STONE)) return DepletedOreBlock.Host.STONE;
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ore.getBlock());
        if (key != null && key.getPath().contains("deepslate")) return DepletedOreBlock.Host.DEEPSLATE;
        if (level.dimension() == Level.NETHER) return DepletedOreBlock.Host.NETHERRACK;
        return DepletedOreBlock.Host.STONE;
    }
}
