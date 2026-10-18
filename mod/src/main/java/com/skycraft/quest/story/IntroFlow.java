package com.skycraft.quest.story;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.QuestConfig;
import com.skycraft.quest.QuestItems;
import com.skycraft.quest.QuestPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The opening, per player and server-authoritative: the state machine ({@link IntroState}, saved in
 * {@code module("intro")}), the cottage as the first spawn, the loading screen after character creation, the window
 * trigger, the cutscene (the dragon itself is drawn by the client for that player only), the key, the one-way door, the
 * players hidden from each other, and the guards that keep a player in the cottage until the key is used.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class IntroFlow {
    static final String MODULE = "intro";
    private static final String STATE = "state";

    /** Transient per-player bookkeeping for players inside the opening. */
    private static final class Run {
        IntroState state;
        long since;
        boolean clientLoaded;
        long cutStart;
        Vec3 lock = Vec3.ZERO;
        int cards;
        long lastProgress;
        long lastGuard;
    }

    private static final Map<UUID, Run> RUNS = new ConcurrentHashMap<>();
    /** Players hidden from every other client: all those whose state is not LEFT. */
    private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();

    private IntroFlow() {}

    // ------------------------------------------------------------------ state

    @Nullable
    public static IntroState state(Player p) {
        Run r = RUNS.get(p.getUUID());
        if (r != null) return r.state;
        return stored(SkyData.get(p));
    }

    @Nullable
    private static IntroState stored(PlayerData d) {
        CompoundTag t = d.module(MODULE);
        return t.contains(STATE) ? IntroState.byName(t.getStringOr(STATE, "")) : null;
    }

    /** True while this player is inside the opening (hidden, locked in). */
    public static boolean inIntro(Player p) {
        IntroState s = state(p);
        return s != null && s.inIntro();
    }

    /** True when the player has not yet seen the dragon attack (the town does not smoke for them). */
    public static boolean beforeAttack(Player p) {
        IntroState s = state(p);
        return s != null && s.beforeAttack();
    }

    private static boolean lockOn() {
        return QuestConfig.INTRO_LOCK_EXIT.get();
    }

    private static void setState(ServerPlayer p, IntroState st) {
        PlayerData d = SkyData.get(p);
        d.module(MODULE).putString(STATE, st.name());
        d.markDirty();
        Run r = RUNS.get(p.getUUID());
        if (st == IntroState.LEFT) {
            RUNS.remove(p.getUUID());
        } else {
            if (r == null) {
                r = new Run();
                RUNS.put(p.getUUID(), r);
            }
            r.state = st;
            r.since = p.level().getGameTime();
            if (st == IntroState.LOADING) r.clientLoaded = false;
        }
        boolean hide = st.inIntro() && QuestConfig.INTRO_HIDE_PLAYERS.get();
        boolean changed = hide ? HIDDEN.add(p.getUUID()) : HIDDEN.remove(p.getUUID());
        sendState(p, st);
        if (changed) syncHidden();
    }

    /** The world-loading screen never lasts less than this (ticks): long enough to read, and the far chunks arrive meanwhile. */
    static final int MIN_LOADING_TICKS = 6 * 20;

    private static void sendState(ServerPlayer p, IntroState st) {
        int[] h = StartCottage.helgen();
        float progress = StartCottage.prepProgress(p.level());
        // the chunks the client must have before the screen lifts: 8 around the cottage and the whole town (its radius + 16)
        send(p, new IntroPackets.State(st.ordinal(), progress, h[0] >> 4, h[1] >> 4, StartCottage.townRadius() + 16, 8,
                QuestConfig.INTRO_LOADING_SECONDS.get()));
    }

    /** Tells a client that is not in the opening so (it holds the world back until it knows): state -1. */
    private static void sendNone(ServerPlayer p) {
        send(p, new IntroPackets.State(-1, 1f, 0, 0, 0, 0, 0));
    }

    private static void syncHidden() {
        try {
            SkyNetwork.sendToAll(new IntroPackets.Hidden(new ArrayList<>(HIDDEN)));
        } catch (RuntimeException e) {
            // no listener (a test run without a server connection)
        }
    }

    /** Sends to one player; a stand-in without a connection (the selftest) just doesn't receive it. */
    private static void send(ServerPlayer p, Object msg) {
        try {
            SkyNetwork.sendToPlayer(p, msg);
        } catch (RuntimeException e) {
            // no connection
        }
    }

    // ------------------------------------------------------------------ first join, login

    /**
     * A brand-new character is put in the cottage BEFORE the connection exists: this fires while the server loads the
     * player's data (no data file yet), before the dimension, the login packet and the first chunk packets are sent, so
     * the client's very first position is the cottage floor and the first chunks it receives are the cottage's. (The
     * later {@link #onFirstJoin} only finishes the state; its teleport is then a no-op.)
     */
    @SubscribeEvent
    public static void onLoadPlayer(PlayerEvent.LoadFromFile event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.connection != null) return; // not a login in progress
        // an existing character has its data file (getPlayerFile("dat") throws: the suffix is reserved)
        if (new java.io.File(event.getPlayerDirectory(), event.getPlayerUUID() + ".dat").exists()) return; // resumed in onLogin
        try {
            placeFirstJoin(p);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Could not place the new character in the start cottage", e);
        }
    }

    /** Moves a new player to the cottage floor (facing the window). Returns false when there is no cottage here. */
    static boolean placeFirstJoin(ServerPlayer p) {
        if (!QuestConfig.INTRO.get() || p.level().dimension() != Level.OVERWORLD || !(p.level() instanceof ServerLevel level)) return false;
        StartCottage.Site site = StartCottage.ensure(level);
        if (site == null) return false;
        Vec3 w = site.wake();
        p.snapTo(w.x, w.y + 0.05, w.z, site.yaw(), 8f);
        p.fallDistance = 0f;
        p.setDeltaMovement(Vec3.ZERO);
        return true;
    }

    /** Builds the cottage while the world loads (behind the loading screen), so no player ever waits for it on joining. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (event.getServer().overworld() == null) return;
        if (!QuestConfig.INTRO.get()) return;
        try {
            StartCottage.prebuild(event.getServer().overworld());
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Start cottage prebuild failed (it is built on the first join instead)", e);
        }
    }

    /**
     * First login of a new character: the cottage is the first spawn (the world is not entered at the gate and moved).
     * Returns true when the player was placed in the cottage (the caller then skips the world-spawn placement).
     */
    public static boolean onFirstJoin(ServerPlayer p) {
        if (!QuestConfig.INTRO.get() || p.level().dimension() != Level.OVERWORLD || !(p.level() instanceof ServerLevel level)) {
            markLeft(p);
            return false;
        }
        StartCottage.Site site;
        try {
            site = StartCottage.ensure(level);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Start cottage could not be built; skipping the opening", e);
            site = null;
        }
        if (site == null) {
            markLeft(p);
            return false;
        }
        setState(p, IntroState.JOINED);
        StartCottage.wake(p);
        return true;
    }

    private static void markLeft(ServerPlayer p) {
        PlayerData d = SkyData.get(p);
        d.module(MODULE).putString(STATE, IntroState.LEFT.name());
        d.markDirty();
    }

    /** Every login, after {@link #onFirstJoin}: resumes a saved state or decides what an old save does. */
    public static void onLogin(ServerPlayer p) {
        PlayerData d = SkyData.get(p);
        IntroState st = stored(d);
        if (st == null) {
            // a character made before the cottage opening
            st = QuestConfig.INTRO.get() && p.level().dimension() == Level.OVERWORLD && migrate(p, d) ? IntroState.JOINED : IntroState.LEFT;
            if (st == IntroState.LEFT) {
                markLeft(p);
            } else {
                ServerLevel level = p.level();
                StartCottage.Site site = null;
                try {
                    site = StartCottage.ensure(level);
                } catch (RuntimeException e) {
                    Skycraft.LOGGER.error("Start cottage could not be built for an existing character", e);
                }
                if (site == null) {
                    markLeft(p);
                } else {
                    setState(p, IntroState.JOINED);
                    StartCottage.wake(p);
                }
            }
            st = stored(d);
        }
        if (st == null || !st.inIntro()) {
            sendNone(p);
            syncHidden(p);
            return;
        }
        // resume: back to a state that can be continued, and back into the cottage
        if (st == IntroState.CUTSCENE) st = IntroState.IN_COTTAGE;
        if (st == IntroState.LOADING || st == IntroState.IN_COTTAGE) {
            if (SkyData.get(p).getRace() == null) st = IntroState.CHARACTER;
        }
        setState(p, st);
        if (p.level() instanceof ServerLevel level && p.level().dimension() == Level.OVERWORLD) {
            StartCottage.Site site = StartCottage.ensure(level);
            if (site != null && p.position().distanceToSqr(site.centre()) > 20 * 20) StartCottage.wake(p);
        }
        if (st == IntroState.KEY_GIVEN) ensureKey(p);
        syncHidden();
    }

    private static void syncHidden(ServerPlayer p) {
        send(p, new IntroPackets.Hidden(new ArrayList<>(HIDDEN)));
    }

    /** Should a character that already exists play the opening? */
    private static boolean migrate(ServerPlayer p, PlayerData d) {
        if (QuestConfig.INTRO_EXISTING_SAVES.get() != QuestConfig.IntroMigration.REPLAY_IF_UNFINISHED) return false;
        if (!QuestConfig.MAIN_QUEST.get()) return false;
        if (!d.module("core").getBooleanOr("spawn_placed",false)) return false; // a brand-new character takes the first-join path
        if (Story.completed(d, "main_unbound") || Story.hasFlag(d, "helgen_arrived") || Story.hasFlag(d, "left_cottage")) return false;
        return true;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        RUNS.remove(id);
        if (HIDDEN.remove(id)) syncHidden();
    }

    // ------------------------------------------------------------------ the loop

    /** Server tick: the cottage's path and cone preparation (a no-op without a Tamriel world or when done). */
    public static void serverTick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level != null && level.getGameTime() % 2 == 0) StartCottage.tickPrep(level);
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer p)) return;
        Run r = RUNS.get(p.getUUID());
        if (r == null) return;
        try {
            tick(p, r);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Intro tick failed for {}", p.getName().getString(), e);
        }
    }

    private static void tick(ServerPlayer p, Run r) {
        ServerLevel level = p.level();
        long now = level.getGameTime();
        PlayerData data = SkyData.get(p);
        switch (r.state) {
            case JOINED, CHARACTER -> {
                if (data.getRace() != null) {
                    setState(p, IntroState.LOADING);
                } else if (r.state == IntroState.JOINED && now - r.since > 40) {
                    setState(p, IntroState.CHARACTER);
                }
            }
            case LOADING -> {
                int timeout = QuestConfig.INTRO_LOADING_SECONDS.get() * 20;
                boolean ready = StartCottage.prepared(level) && r.clientLoaded && now - r.since >= MIN_LOADING_TICKS;
                if (now - r.lastProgress >= 10) {
                    r.lastProgress = now;
                    sendState(p, IntroState.LOADING);
                }
                // the server's own limit is a little longer than the client's, in case its message is lost
                if (ready || now - r.since > timeout + 20 * 20) {
                    if (!ready) Skycraft.LOGGER.info("Intro: loading finished by timeout for {} (prepared {}, client {})",
                            p.getName().getString(), StartCottage.prepared(level), r.clientLoaded);
                    setState(p, IntroState.IN_COTTAGE);
                    card(p, "quest.skycraft.intro2.a", 120);
                    card(p, "quest.skycraft.intro2.hint", 160);
                    p.removeAllEffects();
                }
            }
            case IN_COTTAGE -> {
                {
                    StartCottage.Site site = StartCottage.site(level);
                    if (site != null) {
                        Vec3 spot = site.windowSpot();
                        double dx = p.getX() - spot.x, dz = p.getZ() - spot.z;
                        if (dx * dx + dz * dz <= 1.9 * 1.9 && Math.abs(p.getY() - spot.y) < 1.6) startCutscene(p, r, site);
                    }
                }
                guard(p, r, now);
            }
            case CUTSCENE -> tickCutscene(p, r, now);
            case KEY_GIVEN -> {
                if (now % 100 == 0) ensureKey(p);
                guard(p, r, now);
            }
            default -> {
            }
        }
    }

    /** Keeps the player in the cottage while the opening runs: back inside when they end up away or in another dimension. */
    private static void guard(ServerPlayer p, Run r, long now) {
        if (!lockOn() || now - r.lastGuard < 20) return;
        r.lastGuard = now;
        ServerLevel level = p.level();
        StartCottage.Site site = level.dimension() == Level.OVERWORLD ? StartCottage.site(level) : null;
        boolean away = site == null || p.position().distanceToSqr(site.centre()) > 14 * 14;
        if (away && !p.isCreative() && !p.isSpectator()) {
            ServerLevel over = p.level().getServer().overworld();
            StartCottage.Site s = StartCottage.site(over);
            if (s == null) return;
            Vec3 w = s.wake();
            StartCottage.moveTo(p, over, w.x, w.y + 0.05, w.z, s.yaw(), 8f);
            p.fallDistance = 0f;
            p.setDeltaMovement(Vec3.ZERO);
        }
    }

    // ------------------------------------------------------------------ the cutscene

    /** Narration cards at these ticks into the cutscene (after its start): key suffix. */
    private static final int[][] CARD_AT = {{60, 0}, {330, 1}, {600, 2}, {800, 3}};
    private static final String[] CARD_KEYS = {"quest.skycraft.intro2.b", "quest.skycraft.intro2.c", "quest.skycraft.intro2.d", "quest.skycraft.intro2.e"};
    private static final double[][] BURN_OFFSETS = {{22, -22}, {-26, -26}, {30, 30}, {-14, -10}, {-30, -8}, {-12, -30}, {4, -44}, {-6, 20}, {14, 8}, {-40, 14}, {38, -4}, {-2, -6}};

    private static void startCutscene(ServerPlayer p, Run r, StartCottage.Site site) {
        ServerLevel level = p.level();
        Vec3 spot = site.windowSpot();
        r.lock = spot;
        r.cutStart = level.getGameTime();
        r.cards = 0;
        // stand at the window, looking at the town
        double dx = site.helgenX() + 0.5 - spot.x, dz = site.helgenZ() + 0.5 - spot.z;
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        double hd = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) -Math.toDegrees(Math.atan2(site.helgenY() + 8 - (spot.y + 1.62), hd));
        p.connection.teleport(spot.x, spot.y, spot.z, yaw, pitch);
        p.setDeltaMovement(Vec3.ZERO);
        setState(p, IntroState.CUTSCENE);
        int hr = 60;
        com.skycraft.tamriel.Place pl = com.skycraft.tamriel.Places.byId("helgen").orElse(null);
        if (pl != null) hr = Math.max(40, Math.min(90, pl.radius()));
        double scale = hr / 60.0;
        double[] burn = new double[BURN_OFFSETS.length * 3];
        for (int i = 0; i < BURN_OFFSETS.length; i++) {
            int x = site.helgenX() + (int) Math.round(BURN_OFFSETS[i][0] * scale), z = site.helgenZ() + (int) Math.round(BURN_OFFSETS[i][1] * scale);
            burn[i * 3] = x + 0.5;
            burn[i * 3 + 1] = StartCottage.groundY(level, x, z);
            burn[i * 3 + 2] = z + 0.5;
        }
        int ticks = QuestConfig.INTRO_CUTSCENE_SECONDS.get() * 20;
        int gy = StartCottage.groundY(level, site.helgenX(), site.helgenZ());
        send(p, new IntroPackets.Cutscene(true, ticks, spot.x, spot.y + 1.62, spot.z, yaw, pitch,
                site.helgenX() + 0.5, gy, site.helgenZ() + 0.5, hr, level.getSeed() ^ p.getUUID().getLeastSignificantBits(), burn));
    }

    private static void tickCutscene(ServerPlayer p, Run r, long now) {
        long t = now - r.cutStart;
        // controls stopped: the server puts them back the moment they drift (the client also ignores input)
        if (p.position().distanceToSqr(r.lock.x, p.getY(), r.lock.z) > 0.09 || Math.abs(p.getY() - r.lock.y) > 0.6) {
            p.connection.teleport(r.lock.x, r.lock.y, r.lock.z, p.getYRot(), p.getXRot());
        }
        p.setDeltaMovement(Vec3.ZERO);
        double scale = QuestConfig.INTRO_CUTSCENE_SECONDS.get() / 48.0;
        while (r.cards < CARD_AT.length && t >= CARD_AT[r.cards][0] * scale) {
            card(p, CARD_KEYS[CARD_AT[r.cards][1]], (int) (200 * scale));
            r.cards++;
        }
        if (t >= QuestConfig.INTRO_CUTSCENE_SECONDS.get() * 20L) endCutscene(p);
    }

    private static void endCutscene(ServerPlayer p) {
        send(p, new IntroPackets.Cutscene(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, new double[0]));
        ensureKey(p);
        setState(p, IntroState.KEY_GIVEN);
        Story.setFlag(SkyData.get(p), "intro_cottage", true);
        Story.setFlag(SkyData.get(p), "intro_played", true);
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 70, 25));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                Component.translatable("quest.skycraft.intro2.title").withStyle(ChatFormatting.GOLD)));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                Component.translatable("quest.skycraft.intro2.subtitle").withStyle(ChatFormatting.GRAY)));
        Scenes.journal(p.level().getServer(), p);
        Notifier.message(p, Component.translatable("quest.skycraft.intro2.key").withStyle(ChatFormatting.YELLOW));
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6f, 0.8f);
    }

    private static void card(ServerPlayer p, String key, int stay) {
        send(p, new QuestPackets.NarrationCard(Component.translatable(key), stay));
    }

    // ------------------------------------------------------------------ the key and the door

    private static boolean isKey(ItemStack s, UUID owner) {
        return !s.isEmpty() && s.is(QuestItems.COTTAGE_KEY.get()) && com.skycraft.inventory.ItemData.custom(s) != null && com.skycraft.inventory.ItemData.custom(s).read("owner", net.minecraft.core.UUIDUtil.CODEC).isPresent()
                && owner.equals(com.skycraft.inventory.ItemData.custom(s).read("owner", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
    }

    private static boolean hasKey(ServerPlayer p) {
        return com.skycraft.inventory.Inv.has(p, s -> isKey(s, p.getUUID()));
    }

    private static void ensureKey(ServerPlayer p) {
        if (hasKey(p)) return;
        ItemStack key = QuestItems.cottageKey(p.getUUID());
        com.skycraft.inventory.Pack.giveOrDrop(p, key);
    }

    private static void removeKeys(ServerPlayer p) {
        com.skycraft.inventory.Inv.take(p, s -> isKey(s, p.getUUID()), Integer.MAX_VALUE);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getLevel() instanceof ServerLevel level)) return;
        IntroState st = state(p);
        // nothing is done with the world while the cutscene runs
        if (st == IntroState.CUTSCENE) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        if (!StartCottage.isDoor(level, event.getPos())) return;
        if (p.isCreative() && p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (st == IntroState.KEY_GIVEN && hasKey(p)) {
            leave(p, level);
        } else if (st != null && st.inIntro()) {
            level.playSound(null, event.getPos(), SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 0.8f, 1.1f);
            Notifier.message(p, Component.translatable("quest.skycraft.intro2.locked").withStyle(ChatFormatting.GRAY));
        } else {
            level.playSound(null, event.getPos(), SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 0.8f, 1.1f);
            Notifier.message(p, Component.translatable("quest.skycraft.intro2.locked_in").withStyle(ChatFormatting.GRAY));
        }
    }

    /** The key opens the door once: it is consumed, the player stands at the start of the path, and the cottage is behind them. */
    private static void leave(ServerPlayer p, ServerLevel level) {
        StartCottage.Site site = StartCottage.site(level);
        if (site == null) return;
        removeKeys(p);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.PLAYERS, 0.9f, 1.5f);
        Vec3 out = StartCottage.exit(level, site);
        StartCottage.moveTo(p, level, out.x, out.y + 0.05, out.z, StartCottage.exitYaw(site), 0f);
        p.fallDistance = 0f;
        p.setDeltaMovement(Vec3.ZERO);
        Story.setFlag(SkyData.get(p), "left_cottage", true);
        // the cottage bed must not be where a death returns them: the door is one way, so the path's start is
        com.skycraft.combat.RespawnPoints.set(p, level.dimension(), net.minecraft.core.BlockPos.containing(out));
        setState(p, IntroState.LEFT);
        level.playSound(null, out.x, out.y, out.z, SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 0.8f, 0.9f);
        Notifier.message(p, Component.translatable("quest.skycraft.intro2.left").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    /** The key stays with its owner: it can't be thrown away. */
    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        ItemStack s = event.getEntity().getItem();
        if (!s.is(QuestItems.COTTAGE_KEY.get())) return;
        // the dropped stack has already left the inventory: cancelling would lose it, so the key is made again
        event.setCanceled(true);
        if (event.getPlayer() instanceof ServerPlayer p && state(p) == IntroState.KEY_GIVEN) ensureKey(p);
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) return;
        event.getDrops().removeIf(e -> e.getItem().is(QuestItems.COTTAGE_KEY.get()));
    }

    // ------------------------------------------------------------------ guards

    @SubscribeEvent
    public static void onTeleport(EntityTeleportEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && lockOn() && inIntro(p) && !p.isCreative()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if (event.getPlayer() instanceof ServerPlayer p && lockOn() && inIntro(p) && !p.isCreative()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && lockOn() && inIntro(p) && !p.isCreative()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onHurt(com.skycraft.combat.BeforeArmorDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && inIntro(p) && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            event.setCanceled(true);
        }
    }

    /** Players in the opening can't be targeted: not by mobs, not by other players. */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof ServerPlayer p && inIntro(p)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onAttack(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer victim && event.getSource().getEntity() instanceof ServerPlayer attacker
                && victim != attacker && (inIntro(victim) || inIntro(attacker))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityUse(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getTarget() instanceof ServerPlayer target && target != p
                && (inIntro(target) || inIntro(p))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer p && state(p) == IntroState.CUTSCENE) event.setCanceled(true);
    }

    // ------------------------------------------------------------------ operator tools

    /** Sets a player's opening back to the start (they wake in the cottage again); true when it could be done. */
    public static boolean restart(ServerPlayer p) {
        ServerLevel level = p.level().getServer().overworld();
        StartCottage.Site site = StartCottage.ensure(level);
        if (site == null) return false;
        removeKeys(p);
        Story.setFlag(SkyData.get(p), "left_cottage", false);
        if (p.level() != level) {
            StartCottage.moveTo(p, level, site.wake().x, site.wake().y, site.wake().z, site.yaw(), 8f);
        }
        setState(p, SkyData.get(p).getRace() == null ? IntroState.JOINED : IntroState.LOADING);
        StartCottage.wake(p);
        return true;
    }

    /** Marks the player's opening as done (they stay where they are). */
    public static void skip(ServerPlayer p) {
        removeKeys(p);
        setState(p, IntroState.LEFT);
    }

    /** Admin/selftest: forces a state without side effects beyond the packets. */
    static void force(ServerPlayer p, IntroState st) {
        setState(p, st);
    }

    // ---- selftest hooks (a stand-in player without a connection drives the same code the real one does)

    static void testTick(ServerPlayer p) {
        Run r = RUNS.get(p.getUUID());
        if (r != null) tick(p, r);
    }

    static void testFastForwardLoading(ServerPlayer p) {
        Run r = RUNS.get(p.getUUID());
        if (r != null) r.since -= MIN_LOADING_TICKS + 5;
    }

    static boolean testPlaceFirstJoin(ServerPlayer p) {
        return placeFirstJoin(p);
    }

    static void testFastForwardCutscene(ServerPlayer p) {
        Run r = RUNS.get(p.getUUID());
        if (r != null) r.cutStart -= 20L * QuestConfig.INTRO_CUTSCENE_SECONDS.get() + 5;
    }

    static boolean testHasKey(ServerPlayer p) {
        return hasKey(p);
    }

    static boolean testUseDoor(ServerPlayer p) {
        if (state(p) != IntroState.KEY_GIVEN || !hasKey(p)) return false;
        leave(p, p.level());
        return true;
    }

    static void testForget(ServerPlayer p) {
        RUNS.remove(p.getUUID());
        if (HIDDEN.remove(p.getUUID())) syncHidden();
        p.getInventory().clearContent();
        com.skycraft.inventory.Pack.clear(p);
        SkyData.get(p).module(MODULE).remove(STATE);
    }

    static boolean testHidden(ServerPlayer p) {
        return HIDDEN.contains(p.getUUID());
    }

    /** Selftest: the client's "loaded" message, as the packet handler does it. */
    static void onClientLoaded(ServerPlayer p) {
        Run r = RUNS.get(p.getUUID());
        if (r != null && r.state == IntroState.LOADING) r.clientLoaded = true;
    }

    public static List<UUID> hidden() {
        return new ArrayList<>(HIDDEN);
    }

    /** Chunk position of the town centre (for the loading screen). */
    static ChunkPos townChunk() {
        int[] h = StartCottage.helgen();
        return new ChunkPos(h[0] >> 4, h[1] >> 4);
    }

    public static void reset() {
        RUNS.clear();
        HIDDEN.clear();
    }
}
