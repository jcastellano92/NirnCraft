package com.skycraft.protect;

import com.skycraft.Skycraft;
import com.skycraft.homestead.HomesteadClaim;
import com.skycraft.homestead.HomesteadData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.entity.EntityMobGriefingEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.BonemealEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The world protection handlers (see {@link Protect}): player breaking and placing, and the "griefing off" rules for
 * explosions, mobs, fire, fluids, pistons, tools and decor entities. Handlers are cheap: a claim lookup (a handful
 * of homesteads) and, for the few block classes that need it, a cached footprint query.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class ProtectEvents {
    /** Fire blocks scripted scenes keep burning ({@code skycraft:persistent_fire}, added by the Helgen scene). */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> PERSISTENT_FIRE = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK, new net.minecraft.resources.ResourceLocation(Skycraft.MODID, "persistent_fire"));

    private ProtectEvents() {}

    // ------------------------------------------------------------------ players: breaking and placing

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)) return;
        Protect.Decision d = Protect.checkBreak(player, level, event.getPos(), event.getState());
        if (d.allowed()) return;
        event.setCanceled(true);
        Protect.hint(player, d);
        denied(player, event.getPos(), event.getState());
    }

    /** Tells the client a block will not break, so it stops predicting it (no crack, no ghost removal). */
    private static void denied(ServerPlayer player, BlockPos pos, BlockState state) {
        if (player instanceof FakePlayer || player.connection == null) return;
        ProtectPackets.sendDenied(player, pos, String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
    }

    // ------------------------------------------------------------------ the client's picture of the rules

    private static final Map<UUID, Integer> LAST_POLICY = new HashMap<>();
    private static final int POLICY_RANGE = 320;

    /** Keeps each client's picture of the claims around it (and of whether the rules apply to it) current. */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player) || player.tickCount % 40 != 7) return;
        if (player instanceof FakePlayer || player.connection == null) return;
        boolean enabled = Protect.enabled();
        boolean bypass = Protect.bypassed(player);
        ServerLevel level = player.serverLevel();
        List<ProtectPackets.ClaimView> views = new ArrayList<>();
        if (enabled && !bypass) {
            String dim = level.dimension().location().toString();
            for (HomesteadClaim c : HomesteadData.get(level.getServer()).allClaims()) {
                if (!dim.equals(c.getDimension())) continue;
                BlockPos at = c.getCenter();
                long dx = at.getX() - player.getBlockX(), dz = at.getZ() - player.getBlockZ();
                long reach = POLICY_RANGE + c.getRadius();
                if (dx * dx + dz * dz > reach * reach) continue;
                views.add(new ProtectPackets.ClaimView(at.getX(), at.getY(), at.getZ(), c.getRadius(), c.getMinDepth(),
                        c.getMaxHeight(), c.canBuild(player)));
            }
        }
        int hash = Objects.hash(enabled, bypass, views);
        Integer last = LAST_POLICY.put(player.getUUID(), hash);
        if (last != null && last == hash) return;
        ProtectPackets.sendPolicy(player, new ProtectPackets.Policy(enabled, bypass, views));
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        Protect.Decision d = Protect.checkPlace(player, level, event.getPos(), event.getPlacedBlock());
        if (d.allowed()) return;
        event.setCanceled(true);
        Protect.hint(player, d);
    }

    /** Starting to hit a protected block already says no (and why), before any cracks. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide() || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = level.getBlockState(event.getPos());
        if (state.isAir()) return;
        Protect.Decision d = Protect.checkBreak(player, level, event.getPos(), state);
        if (d.allowed()) return;
        event.setCanceled(true);
        Protect.hint(player, d);
        denied(player, event.getPos(), state);
    }

    /** Hoes, axes and shovels do not till, strip or flatten the world outside a homestead. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onToolAction(BlockEvent.BlockToolModificationEvent event) {
        if (!ProtectConfig.TOOL_ACTIONS_OFF.get() || !Protect.enabled()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (Protect.bypassed(player) || Protect.mayBuildAt(player, level, event.getPos())) return;
        event.setCanceled(true);
        if (!event.isSimulated()) Protect.hint(player, "message.skycraft.protect.break");
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBonemeal(BonemealEvent event) {
        if (!ProtectConfig.TOOL_ACTIONS_OFF.get() || !Protect.enabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (Protect.bypassed(player) || Protect.mayBuildAt(player, level, event.getPos())) return;
        event.setCanceled(true);
        Protect.hint(player, "message.skycraft.protect.break");
    }

    /** Buckets: no pouring water or lava outside a homestead; no scooping out a town's well or fountain. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBucket(FillBucketEvent event) {
        if (!ProtectConfig.FLUIDS_OFF.get() || !Protect.enabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (Protect.bypassed(player) || !(event.getTarget() instanceof BlockHitResult hit)) return;
        BlockPos clicked = hit.getBlockPos();
        BlockPos target = clicked.relative(hit.getDirection());
        boolean pouring = event.getEmptyBucket().getItem() instanceof BucketItem bucket && bucket.getFluid() != Fluids.EMPTY;
        if (pouring) {
            if (Protect.mayBuildAt(player, level, target) || Protect.mayBuildAt(player, level, clicked)) return;
            event.setCanceled(true);
            Protect.hint(player, "message.skycraft.protect.fluid");
            return;
        }
        // scooping a source: fine in the wild, not out of a settlement's well, a road's ditch or a structure's pool
        if (Protect.mayBuildAt(player, level, clicked) || !Footprints.at(level, clicked).built()) return;
        event.setCanceled(true);
        Protect.hint(player, "message.skycraft.protect.break");
    }

    // ------------------------------------------------------------------ decor entities

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttackDecor(AttackEntityEvent event) {
        if (!ProtectConfig.PROTECT_ENTITIES.get() || !Protect.enabled()) return;
        Entity target = event.getTarget();
        if (!isDecor(target) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (Protect.bypassed(player) || Protect.mayBuildAt(player, player.serverLevel(), target.blockPosition())) return;
        event.setCanceled(true);
        Protect.hint(player, "message.skycraft.protect.break");
    }

    /** Arrows and spells do not knock paintings, frames and armor stands off the wall either. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onProjectileDecor(ProjectileImpactEvent event) {
        if (!ProtectConfig.PROTECT_ENTITIES.get() || !Protect.enabled()) return;
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit) || !isDecor(hit.getEntity())) return;
        Entity shooter = event.getProjectile().getOwner();
        if (!(hit.getEntity().level() instanceof ServerLevel level)) return;
        if (Protect.bypassed(shooter instanceof Player p ? p : null)) return;
        if (shooter instanceof Player p && Protect.mayBuildAt(p, level, hit.getEntity().blockPosition())) return;
        event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
    }

    private static boolean isDecor(Entity e) {
        return e instanceof HangingEntity || e instanceof ArmorStand;
    }

    // ------------------------------------------------------------------ griefing off

    /** True for the entities that live and work among people (their block work is their job, not griefing). */
    private static boolean isTownsperson(Entity e) {
        return e instanceof AbstractVillager || e instanceof com.skycraft.society.entity.NpcEntity;
    }

    /** Creepers, TNT, ghasts and dragon fire break no blocks (and no paintings); a player's own TNT does, at home. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!ProtectConfig.GRIEFING_OFF.get() || !Protect.enabled() || !(event.getLevel() instanceof ServerLevel level)) return;
        Explosion explosion = event.getExplosion();
        ServerPlayer owner = explosion.getIndirectSourceEntity() instanceof ServerPlayer sp ? sp : null;
        // owner decision (playtest 4): an explosion hurts and throws, it never destroys a block, a player's own TNT in
        // their own homestead included. (A bypassing op's blast is theirs to make.)
        if (owner == null || !Protect.bypassed(owner)) event.getAffectedBlocks().clear();
        if (ProtectConfig.PROTECT_ENTITIES.get()) {
            event.getAffectedEntities().removeIf(e -> isDecor(e)
                    && (owner == null || !Protect.bypassed(owner) && !Protect.mayBuildAt(owner, level, e.blockPosition())));
        }
    }

    /** Endermen, ravagers, silverfish, zombies at doors, snow golems, foxes...: no block work outside of town life. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        Entity e = event.getEntity();
        if (!ProtectConfig.GRIEFING_OFF.get() || !Protect.enabled() || e == null || e.level().isClientSide) return;
        if (isTownsperson(e)) return;
        event.setResult(Event.Result.DENY);
    }

    /** The wither's and the dragon's block breaking. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        if (!ProtectConfig.GRIEFING_OFF.get() || !Protect.enabled() || event.getEntity().level().isClientSide) return;
        if (isTownsperson(event.getEntity())) return;
        event.setCanceled(true);
    }

    /** Lava and water meeting outside a homestead leave things as they were (no stone, cobble or obsidian made). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (!ProtectConfig.FLUIDS_OFF.get() || !Protect.enabled() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (Protect.claimAt(level, event.getPos()) != null) return;
        event.setNewState(event.getOriginalState());
    }

    /**
     * Fire that appears outside a homestead (spread, lightning, lava, flint and steel) goes out on the next tick. Not
     * while the chunk is generated (structures place braziers and campfires then), and not outside the Overworld
     * (the realms and the Nether burn).
     */
    @SubscribeEvent
    public static void onFireAppears(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getState().getBlock() instanceof BaseFireBlock)) return;
        if (!ProtectConfig.FIRE_SPREAD_OFF.get() || !Protect.enabled()) return;
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) return;
        // scripted fires (the burning of Helgen...) are tagged and stay; every other fire goes out at once, in a
        // homestead too (owner decision, playtest 4: fire never spreads and never destroys a block)
        if (event.getState().is(PERSISTENT_FIRE)) return;
        BlockPos pos = event.getPos().immutable();
        server.execute(() -> {
            if (!level.isLoaded(pos)) return;
            BlockState now = level.getBlockState(pos);
            if (now.getBlock() instanceof BaseFireBlock && !now.is(PERSISTENT_FIRE)) level.removeBlock(pos, false);
        });
    }

    /** Farmland is not trampled outside a homestead. */
    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (!ProtectConfig.TOOL_ACTIONS_OFF.get() || !Protect.enabled() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (Protect.claimAt(level, event.getPos()) == null) event.setCanceled(true);
    }

    /**
     * A piston inside a homestead moves only what lies inside the same homestead, and pushes only into it: no
     * reaching across the boundary into a town. (Pistons of structures outside homesteads keep their designs.)
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPiston(PistonEvent.Pre event) {
        if (!ProtectConfig.GRIEFING_OFF.get() || !Protect.enabled() || !(event.getLevel() instanceof ServerLevel level)) return;
        HomesteadClaim home = Protect.claimAt(level, event.getPos());
        if (home == null) return;
        PistonStructureResolver helper = event.getStructureHelper();
        if (helper == null || !helper.resolve()) return;
        Direction push = event.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND ? event.getDirection() : event.getDirection().getOpposite();
        for (BlockPos p : helper.getToPush()) {
            if (Protect.claimAt(level, p) != home || Protect.claimAt(level, p.relative(push)) != home) {
                event.setCanceled(true);
                return;
            }
        }
        for (BlockPos p : helper.getToDestroy()) {
            if (Protect.claimAt(level, p) != home) {
                event.setCanceled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ housekeeping

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Protect.forget(event.getEntity().getUUID());
        LAST_POLICY.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        Protect.clearAll();
    }
}
