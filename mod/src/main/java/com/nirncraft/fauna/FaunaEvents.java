package com.nirncraft.fauna;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Skill;
import com.nirncraft.core.SkyData;
import com.nirncraft.crafting.arcane.block.IngredientPlantBlock;
import com.nirncraft.fauna.entity.MammothEntity;
import com.nirncraft.fauna.entity.SlaughterfishEntity;
import com.nirncraft.skills.Progression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Forge-bus glue of the fauna module: giants' mammoth herds, Hunting XP for beasts that aren't {@code Animal}s, and
 * breaking a harvested alchemy plant (just stems: no drop, no Green Thumb bonus).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class FaunaEvents {
    /** Persistent-data flag on a giant once its herd has been placed. */
    public static final String HERD_FLAG = "nirncraft_mammoth_herd";
    private static final int MAX_MAMMOTHS = 6;
    private static final Deque<Entity> GIANTS = new ArrayDeque<>();

    private FaunaEvents() {}

    // ------------------------------------------------------------------ giants keep mammoth herds

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        EntityType<?> giant = MammothEntity.giantType();
        if (giant != null && entity.getType() == giant && !entity.getPersistentData().getBooleanOr(HERD_FLAG, false)) GIANTS.add(entity);
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        for (int i = 0; i < 4 && !GIANTS.isEmpty(); i++) {
            Entity giant = GIANTS.poll();
            if (giant == null || giant.isRemoved() || !(giant.level() instanceof ServerLevel level)) continue;
            giant.getPersistentData().putBoolean(HERD_FLAG, true);
            spawnHerd(level, giant);
        }
    }

    /**
     * A giant's herd: 2-3 mammoths nearby, tagged {@link MammothEntity#HERD_TAG} so they leave with the giant when no
     * player is around. None if a herd already grazes close by, and never more than {@value #MAX_MAMMOTHS} in the area.
     */
    private static void spawnHerd(ServerLevel level, Entity giant) {
        EntityType<MammothEntity> type = FaunaEntities.MAMMOTH.get();
        if (!level.getEntitiesOfClass(MammothEntity.class, giant.getBoundingBox().inflate(48)).isEmpty()) return;
        int around = level.getEntitiesOfClass(MammothEntity.class, giant.getBoundingBox().inflate(128)).size();
        RandomSource random = level.getRandom();
        int count = Math.min(2 + random.nextInt(2), MAX_MAMMOTHS - around);
        for (int i = 0, tries = 0; i < count && tries < 12; tries++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 6 + random.nextDouble() * 10;
            int x = Mth.floor(giant.getX() + Math.cos(angle) * dist);
            int z = Mth.floor(giant.getZ() + Math.sin(angle) * dist);
            BlockPos column = new BlockPos(x, 0, z);
            if (!level.hasChunkAt(column)) continue;
            BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (Math.abs(pos.getY() - giant.getY()) > 6 || !FaunaSpawns.wild(level, pos)) continue;
            if (!level.noCollision(type.getDimensions().makeBoundingBox(new net.minecraft.world.phys.Vec3(x + 0.5, pos.getY(), z + 0.5)))) continue;
            MammothEntity mammoth = type.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (mammoth == null) break;
            mammoth.snapTo(x + 0.5, pos.getY(), z + 0.5, random.nextFloat() * 360f, 0f);
            mammoth.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
            // a camp's giant stays, and so does its herd; a roaming giant's herd leaves when it does
            if (giant instanceof Mob m && m.isPersistenceRequired()) mammoth.setPersistenceRequired();
            else mammoth.addTag(MammothEntity.HERD_TAG);
            level.addFreshEntity(mammoth);
            i++;
        }
    }

    // ------------------------------------------------------------------ hunting XP for non-Animal beasts

    @SubscribeEvent
    public static void onKill(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide() && com.nirncraft.mounts.Mounts.ownerOf(event.getEntity()) != null) {
            com.nirncraft.mounts.Mounts.onDeath(event.getEntity());
        }

        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (event.getEntity() instanceof SlaughterfishEntity fish) {
            Progression.addSkillXp(player, Skill.HUNTING, 4f + fish.getMaxHealth() * 0.8f);
            SkyData.get(player).addStat("animals_killed", 1);
        }
    }

    // ------------------------------------------------------------------ mounting and taming (horses, camels, guar, wild beasts)

    @SubscribeEvent
    public static void onInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(event.getTarget() instanceof net.minecraft.world.entity.Mob mob) || mob instanceof com.nirncraft.mounts.MountBeast) return;
        if (!(mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse) && !(mob instanceof com.nirncraft.creatures.entity.GuarEntity)
                && com.nirncraft.mounts.MountTaming.wildKind(mob) == null) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            // taming with a saddle, saddlebags, who may ride (the mount system decides; vanilla riding otherwise)
            if (com.nirncraft.mounts.Mounts.interact(player, mob, event.getItemStack())) {
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            }
        } else if (event.getItemStack().is(net.minecraft.world.item.Items.SADDLE) && com.nirncraft.mounts.MountTaming.wildKind(mob) != null
                && !(mob instanceof net.minecraft.world.entity.animal.equine.AbstractHorse)) {
            // client: don't run the beast's own saddle handling
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post)) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            WildHorseTaming.tickServer(player);
        }
    }

    // ------------------------------------------------------------------ harvested plants

    /** Breaking a picked plant only removes the stems (no drop, and none of arcane's Green Thumb bonus). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        BlockState state = event.getState();
        if (!(state.getBlock() instanceof IngredientPlantBlock) || !state.hasProperty(IngredientPlantBlock.HARVESTED)
                || !state.getValue(IngredientPlantBlock.HARVESTED)) return;
        if (event.getPlayer() == null || event.getPlayer().isCreative() || !(event.getLevel() instanceof ServerLevel level)) return;
        event.setCanceled(true);
        level.destroyBlock(event.getPos(), false, event.getPlayer());
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        GIANTS.clear();
    }
}
