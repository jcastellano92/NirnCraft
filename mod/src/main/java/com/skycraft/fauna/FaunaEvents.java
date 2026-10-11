package com.skycraft.fauna;

import com.skycraft.Skycraft;
import com.skycraft.core.Skill;
import com.skycraft.core.SkyData;
import com.skycraft.crafting.arcane.block.IngredientPlantBlock;
import com.skycraft.fauna.entity.MammothEntity;
import com.skycraft.fauna.entity.SlaughterfishEntity;
import com.skycraft.skills.Progression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Forge-bus glue of the fauna module: giants' mammoth herds, Hunting XP for beasts that aren't {@code Animal}s, and
 * breaking a harvested alchemy plant (just stems: no drop, no Green Thumb bonus).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class FaunaEvents {
    /** Persistent-data flag on a giant once its herd has been placed. */
    public static final String HERD_FLAG = "skycraft_mammoth_herd";
    private static final int MAX_MAMMOTHS = 6;
    private static final Deque<Entity> GIANTS = new ArrayDeque<>();

    private FaunaEvents() {}

    // ------------------------------------------------------------------ giants keep mammoth herds

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        EntityType<?> giant = MammothEntity.giantType();
        if (giant != null && entity.getType() == giant && !entity.getPersistentData().getBoolean(HERD_FLAG)) GIANTS.add(entity);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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
        RandomSource random = level.random;
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
            if (!level.noCollision(type.getAABB(x + 0.5, pos.getY(), z + 0.5))) continue;
            MammothEntity mammoth = type.create(level);
            if (mammoth == null) break;
            mammoth.moveTo(x + 0.5, pos.getY(), z + 0.5, random.nextFloat() * 360f, 0f);
            mammoth.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
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
        if (!event.getEntity().level().isClientSide && com.skycraft.mounts.Mounts.ownerOf(event.getEntity()) != null) {
            com.skycraft.mounts.Mounts.onDeath(event.getEntity());
        }

        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (event.getEntity() instanceof SlaughterfishEntity fish) {
            Progression.addSkillXp(player, Skill.HUNTING, 4f + fish.getMaxHealth() * 0.8f);
            SkyData.get(player).addStat("animals_killed", 1);
        }
    }

    // ------------------------------------------------------------------ mounting and taming (horses, camels, guar, wild beasts)

    @SubscribeEvent
    public static void onInteract(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(event.getTarget() instanceof net.minecraft.world.entity.Mob mob) || mob instanceof com.skycraft.mounts.MountBeast) return;
        if (!(mob instanceof net.minecraft.world.entity.animal.horse.AbstractHorse) && !(mob instanceof com.skycraft.creatures.entity.GuarEntity)
                && com.skycraft.mounts.MountTaming.wildKind(mob) == null) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            // taming with a saddle, saddlebags, who may ride (the mount system decides; vanilla riding otherwise)
            if (com.skycraft.mounts.Mounts.interact(player, mob, event.getItemStack())) {
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            }
        } else if (event.getItemStack().is(net.minecraft.world.item.Items.SADDLE) && com.skycraft.mounts.MountTaming.wildKind(mob) != null
                && !(mob instanceof net.minecraft.world.entity.animal.horse.AbstractHorse)) {
            // client: don't run the beast's own saddle handling
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.player instanceof ServerPlayer player) {
            WildHorseTaming.tickServer(player);
        }
    }

    // ------------------------------------------------------------------ harvested plants

    /** Breaking a picked plant only removes the stems (no drop, and none of arcane's Green Thumb bonus). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
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
