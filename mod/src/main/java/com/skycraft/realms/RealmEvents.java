package com.skycraft.realms;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server events of the realms: return points, Sovngarde through vanilla End portals, safe respawns after dying in a
 * realm, first-entry titles, Tsun at the Whalebone Bridge, and tidying the End dragon fight's leftovers in Sovngarde.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class RealmEvents {
    private static final ResourceLocation TSUN = new ResourceLocation(Skycraft.MODID, "tsun");

    private RealmEvents() {}

    /**
     * Before any move into a realm from outside the realms: remember where the player stood. A vanilla End portal
     * would drop players into Sovngarde's ground (and build an obsidian platform there): they go to the misty vale
     * instead.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer p)) return;
        Realm to = Realm.of(event.getDimension());
        Realm from = Realm.of(p.level());
        if (to == null || from != null) return;
        if (p.level().dimension().location().getPath().equals("jail")) return;
        RealmTravel.rememberReturn(p);
        if (to == Realm.SOVNGARDE && p.level().getBlockState(p.blockPosition()).is(Blocks.END_PORTAL)) {
            event.setCanceled(true);
            RealmTravel.schedule(p.server, 1, () -> RealmTravel.enter(p, Realm.SOVNGARDE, RealmTravel.arrival(Realm.SOVNGARDE, p, 0)));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        RealmTravel.tick(event.getServer());
        if (event.getServer().getTickCount() % 40 == 0) {
            ServerLevel end = event.getServer().getLevel(Level.END);
            if (end != null) SovngardeFight.tidy(end);
        }
    }

    // ------------------------------------------------------------------ death and respawn

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().getPersistentData().getBoolean("skycraft_sigil_keeper")
                && event.getEntity().level() instanceof ServerLevel level) {
            // the Sigil Keeper falls: everyone who fought in the tower may take the stone (quests read this flag)
            for (ServerPlayer m : level.players()) {
                if (m.distanceToSqr(event.getEntity()) > 64 * 64) continue;
                com.skycraft.quest.story.Story.setFlag(SkyData.get(m), "sigil_keeper_slain", true);
                SkyData.get(m).markDirty();
            }
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Realm r = Realm.of(p.level());
        if (r == null) return;
        RealmTravel.data(p).putString("died_in", r.id);
        SkyData.get(p).markDirty();
    }

    /** Died in a realm without a bed to wake in: wake where you left Nirn, not at the world's spawn. */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered() || !(event.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag d = RealmTravel.data(p);
        if (!d.contains("died_in")) return;
        d.remove("died_in");
        SkyData.get(p).markDirty();
        if (p.getRespawnPosition() != null || !d.contains("ret") || Realm.of(p.level()) != null) return;
        RealmTravel.schedule(p.server, 2, () -> {
            if (p.isAlive() && Realm.of(p.level()) == null) RealmTravel.returnHome(p);
        });
    }

    // ------------------------------------------------------------------ arriving

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Realm r = Realm.of(event.getTo());
        if (r == null || !r.ownDimension()) return; // the Nether and End already get their titles (world module)
        CompoundTag d = RealmTravel.data(p);
        String key = "seen_" + r.id;
        if (!d.getBoolean(key)) {
            d.putBoolean(key, true);
            SkyData.get(p).markDirty();
            Notifier.title(p, r.displayName(), Component.translatable("realm.skycraft." + r.id + ".sub"));
        } else {
            Notifier.message(p, Component.translatable("realm.skycraft.entering", r.displayName()));
        }
    }

    // ------------------------------------------------------------------ Tsun

    /** Tsun guards the Whalebone Bridge; he sends the living back to Nirn when asked (sneak and speak with him). */
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        ResourceLocation id = EntityType.getKey(event.getTarget().getType());
        if (!TSUN.equals(id)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        boolean alduinDead = SovngardeFight.alduinDefeated(p.serverLevel());
        int line = Math.floorMod((int) (p.level().getGameTime() / 100), 3);
        if (p.isShiftKeyDown() || alduinDead) {
            p.sendSystemMessage(Component.translatable(alduinDead ? "realm.skycraft.tsun.victory" : "realm.skycraft.tsun.return")
                    .withStyle(net.minecraft.ChatFormatting.GOLD));
            p.level().playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 1f, 0.6f);
            RealmTravel.schedule(p.server, 50, () -> {
                if (p.isAlive() && Realm.of(p.level()) == Realm.SOVNGARDE) RealmTravel.returnHome(p);
            });
        } else {
            p.sendSystemMessage(Component.translatable("realm.skycraft.tsun.greet." + line).withStyle(net.minecraft.ChatFormatting.GOLD));
        }
    }

    /** Sovngarde's dragon fight helpers. */
    static final class SovngardeFight {
        private SovngardeFight() {}

        static boolean alduinDefeated(ServerLevel any) {
            ServerLevel end = any.getServer().getLevel(Level.END);
            return end != null && end.getDragonFight() != null && end.getDragonFight().hasPreviouslyKilledDragon();
        }

        /**
         * Once Alduin has fallen the vanilla fight spawns End gateways on a ring 96 blocks out (sunk into Sovngarde's
         * ground, they would lead nowhere): put the ground back.
         */
        static void tidy(ServerLevel end) {
            if (end.getDragonFight() == null || !end.getDragonFight().hasPreviouslyKilledDragon()) return;
            for (int i = 0; i < 20; i++) {
                int x = Mth.floor(96.0 * Math.cos(2.0 * (-Math.PI + 0.15707963267948966 * i)));
                int z = Mth.floor(96.0 * Math.sin(2.0 * (-Math.PI + 0.15707963267948966 * i)));
                BlockPos g = new BlockPos(x, 75, z);
                if (!end.isLoaded(g) || !end.getBlockState(g).is(Blocks.END_GATEWAY)) continue;
                for (BlockPos q : BlockPos.betweenClosed(g.offset(-1, -2, -1), g.offset(1, 2, 1))) {
                    if (end.getBlockState(q).is(Blocks.BEDROCK) || end.getBlockState(q).is(Blocks.END_GATEWAY)) {
                        end.setBlock(q, Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
        }
    }
}
