package com.nirncraft.core;

import com.nirncraft.SkyConfig;
import com.nirncraft.NirnCraft;
import com.nirncraft.network.CorePackets;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.vitals.ActionHandler;
import com.nirncraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
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
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Attachment lifecycle (death copy, login/respawn/dimension sync), first spawn and race prompt. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class PlayerDataEvents {
    /** How long a raceless player is protected while the race menu is pending (5 minutes). */
    private static final int RACE_PROTECTION_TICKS = 20 * 60 * 5;
    /** Persistent-data flag the Illusion invisibility spell sets (magic module); the only invisibility we ever clear. */
    private static final String SPELL_INVISIBILITY = "nirncraft_spell_invisibility";

    /** Server tick at which each raceless player's race prompt (and protection) started this session. */
    private static final Map<UUID, Integer> RACE_PROMPT_START = new ConcurrentHashMap<>();

    /** Where each player last died, until their respawn has been placed. */
    private static final Map<UUID, BlockPos> DEATH_POS = new ConcurrentHashMap<>();

    /** Game time until which a freshly respawned player is invulnerable / left alone by hostile mobs (grace). */
    private static final Map<UUID, Long> RESPAWN_INVULN_UNTIL = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RESPAWN_GRACE_UNTIL = new ConcurrentHashMap<>();

    private PlayerDataEvents() {}

    private static void configureStepHeight(Player player) {
        var attribute = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        if (attribute != null) attribute.setBaseValue(1.0625);
    }

    private static BlockPos respawnPosition(ServerPlayer player) {
        var config = player.getRespawnConfig();
        return config == null ? null : config.respawnData().pos();
    }

    /** True while this player's post-respawn grace (no hostile aggro, the creatures grace period) is running. */
    public static boolean inRespawnGrace(Player player) {
        Long until = RESPAWN_GRACE_UNTIL.get(player.getUUID());
        return until != null && player.level().getGameTime() < until;
    }

    @SubscribeEvent
    public static void clone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        PlayerData old = SkyData.get(original);
        PlayerData now = SkyData.get(event.getEntity());
        now.copyFrom(old);
        if (event.isWasDeath()) {
            now.setMagicka(now.maxMagicka());
            now.setStamina(now.maxStamina());
        }
        if (event.isWasDeath()) DEATH_POS.put(event.getEntity().getUUID(), original.blockPosition());
        configureStepHeight(event.getEntity());
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            configureStepHeight(player);
            // A NirnCraft invisibility spell doesn't survive a relog; potions, Embrace of Shadows etc. are left alone.
            if (player.getPersistentData().getBooleanOr(SPELL_INVISIBILITY, false)) {
                player.getPersistentData().remove(SPELL_INVISIBILITY);
                player.removeEffect(com.nirncraft.platform.Effects.holder(net.minecraft.world.effect.MobEffects.INVISIBILITY));
            }
            fullSync(player);
            PlayerData data = SkyData.get(player);
            if (data.getRace() == null) RACE_PROMPT_START.put(player.getUUID(), player.level().getServer().getTickCount());
            // the player is held (invisible, untargetable, frozen: hold/PlayerHold) until their client reports that its
            // loading is complete; only then the first-join placement and the character select, or the return to the last
            // position (the opening's state, which is what opens the character screen on the client), run
            if (!com.nirncraft.hold.PlayerHold.beginOnLogin(player, () -> afterLoading(player))) afterLoading(player);
        }
    }

    /** Login's second half: the first-join placement and the opening's state (the character select), or the resume. */
    private static void afterLoading(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        if (!data.module("core").getBooleanOr("spawn_placed", false)) {
            data.module("core").putBoolean("spawn_placed", true);
            com.nirncraft.combat.Sheathe.setSheathed(player, true);
            data.markDirty();
            // the cottage is the first spawn (set before the character screen, so there is no gate spawn and move);
            // the world spawn is the fallback when there is no cottage (other worlds, intro off)
            if (!com.nirncraft.quest.story.IntroFlow.onFirstJoin(player)) placeAtStart(player);
        }
        com.nirncraft.quest.story.IntroFlow.onLogin(player);
    }

    /**
     * First login: start at the world spawn, which the Tamriel map puts on the road outside Helgen
     * (docs/TAMRIEL.md). Only the spawn chunk is loaded; the safe spot is searched inside it.
     */
    private static void placeAtStart(ServerPlayer player) {
        ServerLevel level = player.level();
        if (level.dimension() != Level.OVERWORLD) return;
        BlockPos target = level.getRespawnData().pos();
        LevelChunk chunk = level.getChunk(target.getX() >> 4, target.getZ() >> 4);

        BlockPos safe = SafeSpawn.find(level, target, 24);
        if (safe == null) safe = findSafeSpot(level, chunk, target);
        if (safe != null) {
            player.teleportTo(level, safe.getX() + 0.5, safe.getY() + 0.05, safe.getZ() + 0.5,
                java.util.Set.of(), player.getYRot(), player.getXRot(), false);
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
                    int topY = Math.min((level.getMaxY() + 1) - 3, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 2);
                    for (int y = topY; y >= Math.max(level.getMinY() + 2, 60); y--) {
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

    /**
     * A death never wakes you in the intro cottage: when the respawn point is only the provisional place to wake (not a
     * home), vanilla's is cleared so the respawn goes to the world spawn and {@link #placeRespawn} then moves you to
     * the nearest town you have discovered.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void onPlayerDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        BlockPos home = com.nirncraft.combat.RespawnPoints.home(player);
        if (home != null) {
            // vanilla moves its respawn to any bed you slept in, somebody else's included: the home is the record
            if (!home.equals(respawnPosition(player)) || ServerPlayer.RespawnConfig.getDimensionOrDefault(player.getRespawnConfig()) != Level.OVERWORLD) {
                player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                        net.minecraft.world.level.storage.LevelData.RespawnData.of(Level.OVERWORLD, home, player.getYRot(), 0f), true), false);
            }
        } else if (respawnPosition(player) != null) {
            player.setRespawnPosition(null, false);
        }
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            configureStepHeight(player);
            fullSync(player);
            // refreshAttributes only clamps down; a fresh body after death starts at full (Skyrim) health.
            if (!event.isEndConquered()) player.setHealth(player.getMaxHealth());
            BlockPos died = DEATH_POS.remove(player.getUUID());
            if (!event.isEndConquered()) {
                long now = player.level().getGameTime();
                int invuln = SkyConfig.RESPAWN_INVULN_SECONDS.get();
                if (invuln > 0) RESPAWN_INVULN_UNTIL.put(player.getUUID(), now + invuln * 20L);
                // the creatures module's hostile grace also keeps camps and wanderers from charging the spawn point
                RESPAWN_GRACE_UNTIL.put(player.getUUID(), now + com.nirncraft.creatures.CreaturesConfig.GRACE_SECONDS.get() * 20L);
                placeRespawn(player, died);
            }
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
    public static BlockPos nearestVisitedSettlement(ServerPlayer player, ServerLevel level, BlockPos from) {
        // the places the map says were discovered, and the towns this player has walked into (their own record)
        java.util.Map<String, com.nirncraft.tamriel.Place> candidates = new java.util.LinkedHashMap<>();
        net.minecraft.nbt.ListTag list = com.nirncraft.world.WorldData.discovered(SkyData.get(player));
        for (int i = 0; i < list.size(); i++) {
            net.minecraft.nbt.CompoundTag loc = list.getCompoundOrEmpty(i);
            if (loc.getBooleanOr("known", false) || !level.dimension().identifier().toString().equals(
                    loc.getStringOr("dim", "").isEmpty() ? "minecraft:overworld" : loc.getStringOr("dim", ""))) continue;
            java.util.Optional<com.nirncraft.tamriel.Place> place = com.nirncraft.world.WorldData.place(loc);
            if (place.isPresent() && place.get().type().isSettlement()) candidates.put(place.get().id(), place.get());
        }
        for (String id : com.nirncraft.combat.RespawnPoints.visitedTowns(player)) {
            com.nirncraft.tamriel.Places.byId(id).filter(p -> p.type().isSettlement()).ifPresent(p -> candidates.put(p.id(), p));
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (com.nirncraft.tamriel.Place p : candidates.values()) {
            if ("homestead".equals(p.variant())) continue;
            double score = p.distSqr(from.getX(), from.getZ()); // the nearest to where you fell, nothing else
            if (score >= bestScore) continue;
            bestScore = score;
            best = restSpot(level, p);
        }
        return best;
    }

    /** Where in a settlement a respawning player appears: the market square, else a gate, else the inn, else its centre. */
    public static BlockPos restSpot(ServerLevel level, com.nirncraft.tamriel.Place p) {
        BlockPos at = new BlockPos(p.x(), level.getRespawnData().pos().getY(), p.z());
        var e = com.nirncraft.tamriel.settlement.SettlementAnchors.find(level, p.id());
        if (e.isPresent()) {
            com.nirncraft.tamriel.settlement.Anchor a = e.get().first(com.nirncraft.tamriel.settlement.AnchorType.MEETING);
            if (a == null) a = e.get().first(com.nirncraft.tamriel.settlement.AnchorType.GATE);
            if (a == null) a = e.get().first(com.nirncraft.tamriel.settlement.AnchorType.INN);
            if (a != null) at = a.pos();
        } else {
            java.util.List<int[]> gates = com.nirncraft.tamriel.settlement.SettlementAnchors.gates(p);
            if (!gates.isEmpty()) at = new BlockPos(gates.get(0)[0], at.getY(), gates.get(0)[1]);
        }
        return at;
    }

    private static void placeRespawn(ServerPlayer player, @javax.annotation.Nullable BlockPos died) {
        ServerLevel level = player.level();
        if (level.dimension() != Level.OVERWORLD) return;
        BlockPos here = player.blockPosition();
        // a bed counts only when it is a home (slept in and usable, a bought house): the intro cottage's bed never is
        BlockPos home = com.nirncraft.combat.RespawnPoints.home(player);
        boolean hasBed = home != null && respawnPosition(player) != null && ServerPlayer.RespawnConfig.getDimensionOrDefault(player.getRespawnConfig()) == Level.OVERWORLD
                && here.distSqr(home) <= 24 * 24; // vanilla could not use the bed (gone, blocked): treat as having none
        BlockPos target = here;
        if (!hasBed) {
            target = level.getRespawnData().pos();
            BlockPos from = died != null ? died : here;
            if (SkyConfig.RESPAWN_MODE.get() == SkyConfig.RespawnMode.VISITED_SETTLEMENT) {
                BlockPos town = nearestVisitedSettlement(player, level, from);
                if (town != null) target = town;
            }
        } else if (SafeSpawn.isSafe(level, here)) {
            return;
        }
        BlockPos safe = SafeSpawn.find(level, target, 40);
        int quiet = SkyConfig.RESPAWN_SAFE_RADIUS.get();
        if (quiet > 0 && (safe == null || hostileNear(level, safe, quiet))) {
            // a bandit camp or wanderer at the spot: try the settlement/spawn ring further out for a hostile-free place
            BlockPos fallback = null;
            BlockPos[] centres = {target, level.getRespawnData().pos(), here};
            int loads = 6; // forced chunk loads allowed for the whole search; the rest only reads loaded chunks
            outer:
            for (BlockPos c : centres) {
                for (int r = 24; r <= 160; r += 24) {
                    for (int k = 0; k < 8; k++) {
                        double a = k * Math.PI / 4 + r * 0.37;
                        BlockPos cand = c.offset((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r));
                        boolean present = level.getChunkSource().getChunkNow(cand.getX() >> 4, cand.getZ() >> 4) != null;
                        BlockPos s = present || loads-- > 0 ? SafeSpawn.find(level, cand, 8) : SafeSpawn.findLoaded(level, cand, 8);
                        if (s != null && !hostileNear(level, s, quiet)) {
                            fallback = s;
                            break outer;
                        }
                    }
                }
            }
            if (fallback != null) safe = fallback;
        }
        if (safe == null) return;
        player.teleportTo(level, safe.getX() + 0.5, safe.getY() + 0.05, safe.getZ() + 0.5,
                java.util.Set.of(), player.getYRot(), player.getXRot(), false);
        player.fallDistance = 0f;
        player.setDeltaMovement(0, 0, 0);
    }

    private static boolean hostileNear(ServerLevel level, BlockPos pos, int radius) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos).inflate(radius, 40, radius);
        return !level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box,
                m -> m instanceof net.minecraft.world.entity.monster.Enemy && m.isAlive()).isEmpty();
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
        com.nirncraft.combat.CombatHandler.forget(id);
        RACE_PROMPT_START.remove(id);
        DEATH_POS.remove(id);
        RESPAWN_INVULN_UNTIL.remove(id);
        RESPAWN_GRACE_UNTIL.remove(id);
    }

    /** Sends the whole player data to its owner right now (login, respawn, dimension change, big changes). */
    public static void fullSync(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        Vitals.refreshAttributes(player);
        data.consumeDirty();
        data.setLastFullSync(player.level().getServer().getTickCount());
        SkyNetwork.sendToPlayer(player, CorePackets.SyncData.of(data));
    }

    /**
     * True while a raceless player is choosing their race: at most {@link #RACE_PROTECTION_TICKS} per session, and
     * only while the race prompt is actually active (enabled, not jailed).
     */
    public static boolean isRacePending(Player player) {
        if (!(player instanceof ServerPlayer sp) || SkyData.get(player).getRace() != null || !SkyConfig.PROMPT_RACE.get()) return false;
        Integer start = RACE_PROMPT_START.get(player.getUUID());
        if (start == null || sp.level().getServer().getTickCount() - start > RACE_PROTECTION_TICKS) return false;
        return !com.nirncraft.crime.Jail.isJailed(sp);
    }

    /** Until a race is chosen, periodically re-open the race menu (the first time shortly after joining). */
    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Auto-clear corpse marker on map and compass when player reaches within 6 blocks of their corpse
        if (player.tickCount % 20 == 0) {
            PlayerData data = SkyData.get(player);
            net.minecraft.nbt.ListTag discovered = com.nirncraft.world.WorldData.discovered(data);
            boolean removedCorpse = false;
            String curDim = player.level().dimension().identifier().toString();
            for (int i = discovered.size() - 1; i >= 0; i--) {
                net.minecraft.nbt.CompoundTag loc = discovered.getCompoundOrEmpty(i);
                String name = loc.getStringOr("name", "");
                String id = loc.getStringOr("id", "");
                if ("Your Corpse".equals(name) || id.startsWith("corpse|")) {
                    String dim = loc.getStringOr("dim", "");
                    if (curDim.equals(dim)) {
                        double cx = loc.getIntOr("x", 0);
                        double cy = loc.getIntOr("y", 0);
                        double cz = loc.getIntOr("z", 0);
                        if (player.distanceToSqr(cx + 0.5, cy + 0.5, cz + 0.5) <= 36.0) {
                            discovered.remove(i);
                            removedCorpse = true;
                        }
                    }
                }
            }
            if (removedCorpse) {
                data.markDirty();
                Notifier.send(player, com.nirncraft.network.NotifyKind.LOCATION_CLEARED,
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
        RACE_PROMPT_START.putIfAbsent(player.getUUID(), player.level().getServer().getTickCount());
        if (!com.nirncraft.crime.Jail.isJailed(player) && player.tickCount % 600 == 60) {
            SkyNetwork.sendToPlayer(player, new CorePackets.OpenScreen(CorePackets.OpenScreen.RACE));
        }
    }

    /** Invulnerable while the race menu is pending (never against /kill or the void). */
    @SubscribeEvent
    public static void onHurt(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Player player && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                && isRacePending(player)) {
            event.setCanceled(true);
        }
        // a few seconds of invulnerability after respawning (never against /kill or the void)
        if (event.getEntity() instanceof ServerPlayer sp && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            Long until = RESPAWN_INVULN_UNTIL.get(sp.getUUID());
            if (until != null) {
                if (sp.level().getGameTime() < until) event.setCanceled(true);
                else RESPAWN_INVULN_UNTIL.remove(sp.getUUID());
            }
        }
    }

    /** Mobs ignore a player whose race menu is pending. Stealth targeting rules live in combat/Stealth. */
    @SubscribeEvent
    public static void onTarget(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof Player player && isRacePending(player)) {
            event.setCanceled(true);
        }
        // respawn grace: hostile mobs (bandit camps included) do not pick the player as a target; one the player
        // struck first may still fight back
        if (event.getNewAboutToBeSetTarget() instanceof ServerPlayer sp && event.getEntity() instanceof net.minecraft.world.entity.monster.Enemy
                && inRespawnGrace(sp) && event.getEntity().getLastHurtByMob() != sp) {
            event.setCanceled(true);
        }
    }

    /**
     * Walk-over auto-pickup is disabled so items require deliberate interaction ([E] / Take) like Skyrim.
     * Note: this cancels every pickup, which makes crime/Theft.onPickup's own pickup cancelling redundant.
     */
    @SubscribeEvent
    public static void pickup(ItemEntityPickupEvent.Pre event) {
        event.setCanPickup(net.minecraft.util.TriState.FALSE);
    }
}
