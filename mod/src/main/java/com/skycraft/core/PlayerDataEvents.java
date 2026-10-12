package com.skycraft.core;

import com.skycraft.SkyConfig;
import com.skycraft.Skycraft;
import com.skycraft.network.CorePackets;
import com.skycraft.network.SkyNetwork;
import com.skycraft.vitals.ActionHandler;
import com.skycraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Capability lifecycle (attach, death copy, sync on login/respawn/dimension change), first spawn and race prompt. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class PlayerDataEvents {
    /** How long a raceless player is protected while the race menu is pending (5 minutes). */
    private static final int RACE_PROTECTION_TICKS = 20 * 60 * 5;
    /** Persistent-data flag the Illusion invisibility spell sets (magic module); the only invisibility we ever clear. */
    private static final String SPELL_INVISIBILITY = "skycraft_spell_invisibility";

    /** Server tick at which each raceless player's race prompt (and protection) started this session. */
    private static final Map<UUID, Integer> RACE_PROMPT_START = new ConcurrentHashMap<>();

    /** Where each player last died, until their respawn has been placed. */
    private static final Map<UUID, BlockPos> DEATH_POS = new ConcurrentHashMap<>();

    private PlayerDataEvents() {}

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            SkyData.Provider provider = new SkyData.Provider();
            event.addCapability(SkyData.KEY, provider);
            event.addListener(provider::invalidate);
        }
    }

    @SubscribeEvent
    public static void clone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        original.reviveCaps();
        PlayerData old = SkyData.get(original);
        PlayerData now = SkyData.get(event.getEntity());
        now.copyFrom(old);
        if (event.isWasDeath()) {
            now.setMagicka(now.maxMagicka());
            now.setStamina(now.maxStamina());
        }
        if (event.isWasDeath()) DEATH_POS.put(event.getEntity().getUUID(), original.blockPosition());
        event.getEntity().setMaxUpStep(1.0625f);
        original.invalidateCaps();
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.setMaxUpStep(1.0625f);
            // A Skycraft invisibility spell doesn't survive a relog; potions, Embrace of Shadows etc. are left alone.
            if (player.getPersistentData().getBoolean(SPELL_INVISIBILITY)) {
                player.getPersistentData().remove(SPELL_INVISIBILITY);
                player.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
            }
            fullSync(player);
            PlayerData data = SkyData.get(player);
            if (data.getRace() == null) RACE_PROMPT_START.put(player.getUUID(), player.server.getTickCount());
            if (!data.module("core").getBoolean("spawn_placed")) {
                data.module("core").putBoolean("spawn_placed", true);
                com.skycraft.combat.Sheathe.setSheathed(player, true);
                data.markDirty();
                placeAtStart(player);
            }
        }
    }

    /**
     * First login: start at the world spawn, which the Tamriel map puts on the road outside Helgen
     * (docs/TAMRIEL.md). Only the spawn chunk is loaded; the safe spot is searched inside it.
     */
    private static void placeAtStart(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD) return;
        BlockPos target = level.getSharedSpawnPos();
        LevelChunk chunk = level.getChunk(target.getX() >> 4, target.getZ() >> 4);

        BlockPos safe = SafeSpawn.find(level, target, 24);
        if (safe == null) safe = findSafeSpot(level, chunk, target);
        if (safe != null) {
            player.teleportTo(level, safe.getX() + 0.5, safe.getY() + 0.05, safe.getZ() + 0.5, player.getYRot(), player.getXRot());
            player.fallDistance = 0f;
            player.setDeltaMovement(0, 0, 0);
        }
    }

    /** A standable spot near {@code target} without leaving its (already loaded) chunk, or null. */
    private static BlockPos findSafeSpot(ServerLevel level, LevelChunk chunk, BlockPos target) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos safe = new BlockPos.MutableBlockPos();
        for (int r = 0; r <= 8; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    int x = target.getX() + dx;
                    int z = target.getZ() + dz;
                    if (x < minX || x > minX + 15 || z < minZ || z > minZ + 15) continue;
                    int topY = Math.min(level.getMaxBuildHeight() - 3, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 2);
                    for (int y = topY; y >= Math.max(level.getMinBuildHeight() + 2, 60); y--) {
                        safe.set(x, y, z);
                        BlockPos below = safe.below();
                        boolean groundSolid = chunk.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
                                && chunk.getFluidState(below).isEmpty();
                        boolean feetAir = chunk.getBlockState(safe).getCollisionShape(level, safe).isEmpty() && chunk.getFluidState(safe).isEmpty();
                        boolean headAir = chunk.getBlockState(safe.above()).getCollisionShape(level, safe.above()).isEmpty() && chunk.getFluidState(safe.above()).isEmpty();
                        if (groundSolid && feetAir && headAir) return safe.immutable();
                    }
                }
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.setMaxUpStep(1.0625f);
            fullSync(player);
            // refreshAttributes only clamps down; a fresh body after death starts at full (Skyrim) health.
            if (!event.isEndConquered()) player.setHealth(player.getMaxHealth());
            BlockPos died = DEATH_POS.remove(player.getUUID());
            if (!event.isEndConquered()) placeRespawn(player, died);
        }
    }

    /**
     * A normal respawn: at the bed (vanilla keeps the last bed, inn room or home), otherwise at the nearest hold
     * capital or city, otherwise the world spawn. The spot is always solid ground, never a treetop.
     */
    /**
     * The settlement this player has discovered (their own record) nearest to {@code from}; ones with an inn count
     * as 30% closer. The target is the market square (meeting anchor), else a gate, else the place's centre.
     */
    @javax.annotation.Nullable
    private static BlockPos nearestVisitedSettlement(ServerPlayer player, ServerLevel level, BlockPos from) {
        net.minecraft.nbt.ListTag list = com.skycraft.world.WorldData.discovered(SkyData.get(player));
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            net.minecraft.nbt.CompoundTag loc = list.getCompound(i);
            if (loc.getBoolean("known") || !level.dimension().location().toString().equals(
                    loc.getString("dim").isEmpty() ? "minecraft:overworld" : loc.getString("dim"))) continue;
            java.util.Optional<com.skycraft.tamriel.Place> place = com.skycraft.world.WorldData.place(loc);
            if (place.isEmpty() || !place.get().type().isSettlement()) continue;
            com.skycraft.tamriel.Place p = place.get();
            java.util.Optional<com.skycraft.tamriel.settlement.SettlementAnchors.Entry> e =
                    com.skycraft.tamriel.settlement.SettlementAnchors.find(level, p.id());
            double score = Math.sqrt(p.distSqr(from.getX(), from.getZ()));
            BlockPos at = new BlockPos(p.x(), level.getSharedSpawnPos().getY(), p.z());
            if (e.isPresent()) {
                if (e.get().first(com.skycraft.tamriel.settlement.AnchorType.INN) != null) score *= 0.7;
                com.skycraft.tamriel.settlement.Anchor m = e.get().first(com.skycraft.tamriel.settlement.AnchorType.MEETING);
                if (m != null) at = m.pos();
            }
            if (score < bestScore) {
                bestScore = score;
                best = at;
            }
        }
        return best;
    }

    private static void placeRespawn(ServerPlayer player, @javax.annotation.Nullable BlockPos died) {
        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD) return;
        BlockPos here = player.blockPosition();
        boolean hasBed = player.getRespawnPosition() != null && player.getRespawnDimension() == Level.OVERWORLD;
        BlockPos target = here;
        if (!hasBed) {
            target = level.getSharedSpawnPos();
            BlockPos from = died != null ? died : here;
            if (SkyConfig.RESPAWN_MODE.get() == SkyConfig.RespawnMode.VISITED_SETTLEMENT) {
                BlockPos town = nearestVisitedSettlement(player, level, from);
                if (town != null) target = town;
            }
        } else if (SafeSpawn.isSafe(level, here)) {
            return;
        }
        BlockPos safe = SafeSpawn.find(level, target, 40);
        if (safe == null) return;
        player.teleportTo(level, safe.getX() + 0.5, safe.getY() + 0.05, safe.getZ() + 0.5, player.getYRot(), player.getXRot());
        player.fallDistance = 0f;
        player.setDeltaMovement(0, 0, 0);
    }

    @SubscribeEvent
    public static void changeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) fullSync(player);
    }

    /** Other players' race, sheathe state and apparel (only the owner gets the full data). */
    @SubscribeEvent
    public static void startTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && event.getEntity() instanceof ServerPlayer viewer) {
            SkyNetwork.sendToPlayer(viewer, new CorePackets.SyncAppearance(target.getId(), SkyData.get(target).appearance()));
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        ActionHandler.forget(id);
        Vitals.forget(id);
        com.skycraft.combat.CombatHandler.forget(id);
        RACE_PROMPT_START.remove(id);
        DEATH_POS.remove(id);
    }

    /** Sends the whole player data to its owner right now (login, respawn, dimension change, big changes). */
    public static void fullSync(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        Vitals.refreshAttributes(player);
        data.consumeDirty();
        data.setLastFullSync(player.server.getTickCount());
        SkyNetwork.sendToPlayer(player, CorePackets.SyncData.of(data));
    }

    /**
     * True while a raceless player is choosing their race: at most {@link #RACE_PROTECTION_TICKS} per session, and
     * only while the race prompt is actually active (enabled, not jailed).
     */
    public static boolean isRacePending(Player player) {
        if (!(player instanceof ServerPlayer sp) || SkyData.get(player).getRace() != null || !SkyConfig.PROMPT_RACE.get()) return false;
        Integer start = RACE_PROMPT_START.get(player.getUUID());
        if (start == null || sp.server.getTickCount() - start > RACE_PROTECTION_TICKS) return false;
        return !com.skycraft.crime.Jail.isJailed(sp);
    }

    /** Until a race is chosen, periodically re-open the race menu (the first time shortly after joining). */
    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        // Auto-clear corpse marker on map and compass when player reaches within 6 blocks of their corpse
        if (player.tickCount % 20 == 0) {
            PlayerData data = SkyData.get(player);
            net.minecraft.nbt.ListTag discovered = com.skycraft.world.WorldData.discovered(data);
            boolean removedCorpse = false;
            String curDim = player.level().dimension().location().toString();
            for (int i = discovered.size() - 1; i >= 0; i--) {
                net.minecraft.nbt.CompoundTag loc = discovered.getCompound(i);
                String name = loc.getString("name");
                String id = loc.getString("id");
                if ("Your Corpse".equals(name) || id.startsWith("corpse|")) {
                    String dim = loc.getString("dim");
                    if (curDim.equals(dim)) {
                        double cx = loc.getInt("x");
                        double cy = loc.getInt("y");
                        double cz = loc.getInt("z");
                        if (player.distanceToSqr(cx + 0.5, cy + 0.5, cz + 0.5) <= 36.0) {
                            discovered.remove(i);
                            removedCorpse = true;
                        }
                    }
                }
            }
            if (removedCorpse) {
                data.markDirty();
                Notifier.send(player, com.skycraft.network.NotifyKind.LOCATION_CLEARED,
                        net.minecraft.network.chat.Component.literal("Corpse Reached"),
                        net.minecraft.network.chat.Component.literal("Death marker cleared"));
            }
        }
        if (!SkyConfig.PROMPT_RACE.get()) return;
        PlayerData data = SkyData.get(player);
        if (data.getRace() != null) {
            if (player.tickCount % 200 == 0) RACE_PROMPT_START.remove(player.getUUID());
            return;
        }
        RACE_PROMPT_START.putIfAbsent(player.getUUID(), player.server.getTickCount());
        if (!com.skycraft.crime.Jail.isJailed(player) && player.tickCount % 600 == 60) {
            SkyNetwork.sendToPlayer(player, new CorePackets.OpenScreen(CorePackets.OpenScreen.RACE));
        }
    }

    /** Invulnerable while the race menu is pending (never against /kill or the void). */
    @SubscribeEvent
    public static void onHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if (event.getEntity() instanceof Player player && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                && isRacePending(player)) {
            event.setCanceled(true);
        }
    }

    /** Mobs ignore a player whose race menu is pending. Stealth targeting rules live in combat/Stealth. */
    @SubscribeEvent
    public static void onTarget(net.minecraftforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getNewTarget() instanceof Player player && isRacePending(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Walk-over auto-pickup is disabled so items require deliberate interaction ([E] / Take) like Skyrim.
     * Note: this cancels every pickup, which makes crime/Theft.onPickup's own pickup cancelling redundant.
     */
    @SubscribeEvent
    public static void pickup(EntityItemPickupEvent event) {
        event.setCanceled(true);
    }
}
