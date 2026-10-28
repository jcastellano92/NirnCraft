package com.nirncraft.devtest;

import com.nirncraft.loot.container.ContainerKind;
import com.nirncraft.loot.container.ContainerStyle;
import com.nirncraft.render.quality.Knob;
import com.nirncraft.society.NpcNames;
import com.nirncraft.society.NpcRole;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared state and configuration for the NirnCraft Dev / Creative Testing Suite.
 * All features here are strictly gated on Creative mode or {@code -Dnirncraft.devTest=true}.
 */
public final class DevTestState {
    private static final boolean PROP_DEV_TEST = Boolean.getBoolean("nirncraft.devTest");
    private static final DateTimeFormatter EVENT_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /** Upper-bound quality profile ceilings selectable with the {@code -} key. */
    public enum QualityCeiling {
        STEAM_DECK("Steam Deck (Handheld)", "DECK", new double[]{
                0.35, 0.35, 0.30, 0.30, 0.40, 0.00, 0.25, 0.35,
                0.30, 0.35, 0.40, 0.35, 0.55, 0.45, 0.00, 0.35
        }),
        IGPU("Handheld / iGPU", "IGPU", new double[]{
                0.48, 0.48, 0.42, 0.42, 0.52, 0.50, 0.40, 0.48,
                0.45, 0.48, 0.55, 0.48, 0.68, 0.58, 0.00, 0.50
        }),
        LOW("Low Desktop", "LOW", new double[]{
                0.62, 0.62, 0.58, 0.58, 0.65, 0.60, 0.55, 0.62,
                0.60, 0.62, 0.70, 0.62, 0.78, 0.70, 0.50, 0.65
        }),
        MEDIUM("Medium Quality", "MID", new double[]{
                0.78, 0.78, 0.75, 0.75, 0.80, 0.80, 0.75, 0.78,
                0.75, 0.78, 0.85, 0.78, 0.88, 0.82, 1.00, 0.80
        }),
        HIGH("High Quality", "HIGH", new double[]{
                0.92, 0.92, 0.90, 0.90, 0.92, 1.00, 0.90, 0.92,
                0.90, 0.92, 0.95, 0.92, 0.96, 0.94, 1.00, 0.95
        }),
        ULTRA_MAX("Max / Native Hardware", "MAX", new double[]{
                1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00,
                1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00
        });

        public static final QualityCeiling[] VALUES = values();
        public final String label;
        public final String shortTag;
        public final double[] knobCaps;

        QualityCeiling(String label, String shortTag, double[] knobCaps) {
            this.label = label;
            this.shortTag = shortTag;
            this.knobCaps = knobCaps;
        }
    }

    /** Performance overlay verbosity in the top-left corner. */
    public enum FpsOverlayMode {
        OFF("Off"),
        BASIC("Basic (Small)"),
        ADVANCED_SMALL("Advanced Small"),
        ADVANCED_LARGE("Advanced Large");

        public static final FpsOverlayMode[] VALUES = values();
        public final String label;

        FpsOverlayMode(String label) {
            this.label = label;
        }

        public FpsOverlayMode next() {
            return VALUES[(ordinal() + 1) % VALUES.length];
        }
    }

    /** One-shot world placement payload armed from {@link SkyrimCreativeScreen}. */
    public static final class OneShotPlacement {
        public enum Kind {
            ITEM_OR_BLOCK,
            LOOT_CHEST,
            NPC,
            ENTITY
        }

        public final Kind kind;
        public final String label;
        public final ItemStack displayStack;
        public final int savedSlot;
        public final ItemStack savedOriginalStack;

        // Loot chest parameters
        public final ContainerKind containerKind;
        public final ContainerStyle containerStyle;
        public final String lootTable;
        public final int lootLevel;

        // NPC / Entity parameters
        public final String entityId;
        public final NpcRole npcRole;
        public final NpcNames.Race npcRace;
        public final boolean npcFemale;
        public final String customName;
        public final boolean forceHostile;
        public final boolean forcePassive;

        private OneShotPlacement(
                Kind kind,
                String label,
                ItemStack displayStack,
                int savedSlot,
                ItemStack savedOriginalStack,
                ContainerKind containerKind,
                ContainerStyle containerStyle,
                String lootTable,
                int lootLevel,
                String entityId,
                NpcRole npcRole,
                NpcNames.Race npcRace,
                boolean npcFemale,
                String customName,
                boolean forceHostile,
                boolean forcePassive) {
            this.kind = kind;
            this.label = label;
            this.displayStack = displayStack;
            this.savedSlot = savedSlot;
            this.savedOriginalStack = savedOriginalStack;
            this.containerKind = containerKind;
            this.containerStyle = containerStyle;
            this.lootTable = lootTable;
            this.lootLevel = lootLevel;
            this.entityId = entityId;
            this.npcRole = npcRole;
            this.npcRace = npcRace;
            this.npcFemale = npcFemale;
            this.customName = customName;
            this.forceHostile = forceHostile;
            this.forcePassive = forcePassive;
        }

        public static OneShotPlacement forItemOrBlock(
                String label, ItemStack stackToPlace, int slot, ItemStack previousInSlot) {
            ItemStack single = stackToPlace.copy();
            single.setCount(1);
            return new OneShotPlacement(
                    Kind.ITEM_OR_BLOCK,
                    label,
                    single,
                    slot,
                    previousInSlot.copy(),
                    ContainerKind.CHEST,
                    ContainerStyle.NORDIC,
                    "",
                    0,
                    "",
                    NpcRole.CITIZEN,
                    NpcNames.Race.NORD,
                    false,
                    "",
                    false,
                    false);
        }

        public static OneShotPlacement forLootChest(
                String label,
                ItemStack displayStack,
                int slot,
                ItemStack previousInSlot,
                ContainerKind kind,
                ContainerStyle style,
                String lootTable,
                int lootLevel) {
            ItemStack single = displayStack.copy();
            single.setCount(1);
            return new OneShotPlacement(
                    Kind.LOOT_CHEST,
                    label,
                    single,
                    slot,
                    previousInSlot.copy(),
                    kind,
                    style,
                    lootTable,
                    lootLevel,
                    "",
                    NpcRole.CITIZEN,
                    NpcNames.Race.NORD,
                    false,
                    "",
                    false,
                    false);
        }

        public static OneShotPlacement forNpc(
                String label,
                ItemStack displayStack,
                int slot,
                ItemStack previousInSlot,
                NpcRole role,
                NpcNames.Race race,
                boolean female,
                String customName,
                boolean forceHostile,
                boolean forcePassive) {
            ItemStack single = displayStack.copy();
            single.setCount(1);
            return new OneShotPlacement(
                    Kind.NPC,
                    label,
                    single,
                    slot,
                    previousInSlot.copy(),
                    ContainerKind.CHEST,
                    ContainerStyle.NORDIC,
                    "",
                    0,
                    "nirncraft:npc",
                    role,
                    race,
                    female,
                    customName == null ? "" : customName,
                    forceHostile,
                    forcePassive);
        }

        public static OneShotPlacement forEntity(
                String label,
                ItemStack displayStack,
                int slot,
                ItemStack previousInSlot,
                String entityId,
                String customName,
                boolean forceHostile,
                boolean forcePassive) {
            ItemStack single = displayStack.copy();
            single.setCount(1);
            return new OneShotPlacement(
                    Kind.ENTITY,
                    label,
                    single,
                    slot,
                    previousInSlot.copy(),
                    ContainerKind.CHEST,
                    ContainerStyle.NORDIC,
                    "",
                    0,
                    entityId,
                    NpcRole.CITIZEN,
                    NpcNames.Race.NORD,
                    false,
                    customName == null ? "" : customName,
                    forceHostile,
                    forcePassive);
        }
    }

    // ------------------------------------------------------------------ live flags & settings
    public static volatile boolean sessionInitialized = false;

    public static volatile QualityCeiling qualityCeiling = QualityCeiling.ULTRA_MAX;
    public static volatile boolean tracedLighting = true;

    public static volatile boolean godMode = true;
    public static volatile boolean flyMode = true;
    public static volatile boolean noHandsMode = true;
    public static volatile boolean questsEnabled = true;
    public static volatile boolean unlockAllMap = true;
    public static volatile boolean noFogOfWar = true;

    // Map overlays
    public static volatile boolean mapBiomeOverlay = false;
    public static volatile boolean mapShowAllLocations = true;
    public static volatile boolean mapShowAllRoads = true;

    // Screen UI / HUD individual toggles
    public static volatile boolean hudMaster = true;
    public static volatile boolean hudCrosshair = true;
    public static volatile boolean hudCompass = true;
    public static volatile boolean hudVitals = false;
    public static volatile boolean hudHotbar = false;
    public static volatile boolean hudNotifications = true;
    public static volatile boolean hudHands = false;

    public static volatile FpsOverlayMode fpsOverlayMode = FpsOverlayMode.BASIC;

    // Smooth time-of-day fader
    public static volatile long targetDayTime = -1L;
    public static volatile double fadingDayTime = -1.0;
    public static volatile int fadingTicksRemaining = 0;

    // On-screen toast notification
    public static volatile String toastTitle = "";
    public static volatile String toastSubtitle = "";
    public static volatile long toastUntilMs = 0L;

    // Active one-shot placement
    @Nullable
    public static volatile OneShotPlacement activePlacement = null;

    // Favorites in SkyrimCreativeScreen
    private static final Set<String> FAVORITE_KEYS = new HashSet<>();

    // Frame-time history (nanoseconds) for FPS / min / max / p50 / p99
    private static final int FRAME_RING_SIZE = 240;
    private static final long[] FRAME_TIMES_NS = new long[FRAME_RING_SIZE];
    private static int frameRingWriteIdx = 0;
    private static int frameRingCount = 0;
    private static long lastFrameTimestampNs = 0L;

    // Action & input history leading up to a capture
    private static final int MAX_EVENTS = 80;
    private static final Deque<String> RECENT_EVENTS = new ArrayDeque<>(MAX_EVENTS);

    private DevTestState() {}

    /**
     * True when the server has authorized this player for Creative / Dev mode (either via server-validated
     * Creative mode or server-synced {@code SkyData} {@code devtest.authorized}).
     */
    public static boolean isActive(@Nullable Player player) {
        if (player == null) return false;
        if (player.isCreative()) return true;
        return com.nirncraft.core.SkyData.get(player).module("devtest").getBooleanOr("authorized", false);
    }

    /** True when dev mode is active from system property or current client state. */
    public static boolean isDevPropertySet() {
        return PROP_DEV_TEST;
    }

    public static void showToast(String title, String subtitle, long durationMs) {
        toastTitle = title == null ? "" : title;
        toastSubtitle = subtitle == null ? "" : subtitle;
        toastUntilMs = System.currentTimeMillis() + durationMs;
    }

    public static QualityCeiling cycleQualityCeiling(int delta) {
        QualityCeiling[] all = QualityCeiling.VALUES;
        int next = Math.floorMod(qualityCeiling.ordinal() + delta, all.length);
        qualityCeiling = all[next];
        recordEvent("QualityCeiling -> " + qualityCeiling.label);
        showToast(
                "PROFILE CEILING: " + qualityCeiling.label.toUpperCase(),
                "Adaptive quality upper bound set to [" + qualityCeiling.shortTag + "] (Press - for Dev Menu)",
                3200L);
        return qualityCeiling;
    }

    public static boolean toggleTracedLighting() {
        tracedLighting = !tracedLighting;
        recordEvent("TracedLighting -> " + (tracedLighting ? "ON" : "OFF (Fallback)"));
        showToast(
                "LIGHTING MODE: " + (tracedLighting ? "TRACED LIGHTING ON" : "FALLBACK / CHEAP LIGHTING"),
                tracedLighting ? "Full near-light & sun shadow tracing enabled" : "Traced lighting bypassed for low-hardware testing",
                3000L);
        return tracedLighting;
    }

    /** Clamps adaptive quality knobs to the active {@link QualityCeiling} and lighting mode. */
    public static void clampKnobs(double[] frameKnobs) {
        if (frameKnobs == null) return;
        QualityCeiling ceiling = qualityCeiling;
        double[] caps = ceiling.knobCaps;
        int n = Math.min(frameKnobs.length, caps.length);
        for (int i = 0; i < n; i++) {
            if (frameKnobs[i] > caps[i]) {
                frameKnobs[i] = caps[i];
            }
        }
        if (!tracedLighting) {
            if (frameKnobs.length > Knob.SHADOW_TAPS.ordinal()) {
                frameKnobs[Knob.SHADOW_TAPS.ordinal()] = Math.min(frameKnobs[Knob.SHADOW_TAPS.ordinal()], 0.20);
            }
            if (frameKnobs.length > Knob.AIR_MARCH.ordinal()) {
                frameKnobs[Knob.AIR_MARCH.ordinal()] = 0.0;
            }
            if (frameKnobs.length > Knob.ATLAS_SHADOWS.ordinal()) {
                frameKnobs[Knob.ATLAS_SHADOWS.ordinal()] = Math.min(frameKnobs[Knob.ATLAS_SHADOWS.ordinal()], 0.34);
            }
        }
    }

    /**
     * Maps number keys {@code 0..9} so {@code 6} = Noon (6000), {@code 0} = Midnight (18000),
     * and digits in between interpolate proportionally around the 24-hour cycle.
     */
    public static long dayTimeForDigit(int digit) {
        // 6 -> 6000 (12:00 Noon)
        // 7 -> 8400 (14:24), 8 -> 10800 (16:48), 9 -> 13200 (19:12 Dusk), 0 -> 18000 (00:00 Midnight)
        // 1..5 -> proportional fade from Midnight (18000) to Noon (30000 / 6000): each step = 2000 ticks (2 hours)
        return switch (digit) {
            case 0 -> 18000L; // Midnight (00:00)
            case 1 -> 20000L; // 02:00 AM
            case 2 -> 22000L; // 04:00 AM
            case 3 -> 0L;     // 06:00 AM (Dawn)
            case 4 -> 2000L;  // 08:00 AM
            case 5 -> 4000L;  // 10:00 AM
            case 6 -> 6000L;  // 12:00 PM (Noon)
            case 7 -> 8500L;  // 02:30 PM (Afternoon)
            case 8 -> 11500L; // 05:30 PM (Golden Hour / Sunset)
            case 9 -> 14250L; // 08:15 PM (Dusk / Twilight)
            default -> 6000L;
        };
    }

    public static String formatDayTime(long dayTime) {
        long tod = Math.floorMod(dayTime, 24000L);
        int totalMinutes = (int) (((tod + 6000L) % 24000L) * 1440L / 24000L);
        int hour24 = (totalMinutes / 60) % 24;
        int minute = totalMinutes % 60;
        int hour12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
        String ampm = hour24 < 12 ? "AM" : "PM";
        String phase;
        if (tod >= 23000L || tod < 1500L) phase = "Dawn";
        else if (tod < 5000L) phase = "Morning";
        else if (tod < 7500L) phase = "Noon";
        else if (tod < 11000L) phase = "Afternoon";
        else if (tod < 13800L) phase = "Dusk / Sunset";
        else if (tod < 16500L) phase = "Evening";
        else if (tod < 19500L) phase = "Midnight";
        else phase = "Late Night";
        return String.format("%d:%02d %s (%s)", hour12, minute, ampm, phase);
    }

    public static void startTimeFade(long currentWorldTime, int keyDigit) {
        long targetTod = dayTimeForDigit(keyDigit);
        long currentTod = Math.floorMod(currentWorldTime, 24000L);
        long dayBase = currentWorldTime - currentTod;
        long diff = targetTod - currentTod;
        if (diff > 12000L) diff -= 24000L;
        else if (diff < -12000L) diff += 24000L;
        targetDayTime = Math.max(0L, dayBase + currentTod + diff);
        fadingDayTime = currentWorldTime;
        fadingTicksRemaining = 18;
        String label = formatDayTime(targetTod);
        recordEvent("Key [" + keyDigit + "] -> Time of Day fade to " + label + " (" + targetTod + "t)");
        showToast("TIME OF DAY: " + label.toUpperCase(), "Key [" + keyDigit + "] — Smoothly fading sky & sun (" + targetTod + " ticks)", 2400L);
    }

    /** True when first-person hands/held items should be hidden. */
    public static boolean shouldHideHands(@Nullable Player player) {
        if (!isActive(player)) return false;
        // Always show hand when the player is holding a one-shot item to place in the world!
        if (activePlacement != null) return false;
        if (!hudMaster) return true;
        if (noHandsMode && !hudHands) return true;
        return false;
    }

    public static boolean shouldShowCrosshair(@Nullable Player player) {
        if (!isActive(player)) return true;
        if (activePlacement != null) return true;
        return hudMaster && hudCrosshair;
    }

    public static boolean shouldShowCompass(@Nullable Player player) {
        if (!isActive(player)) return true;
        return hudMaster && hudCompass;
    }

    public static boolean shouldShowVitals(@Nullable Player player) {
        if (!isActive(player)) return true;
        return hudMaster && hudVitals;
    }

    public static boolean shouldShowHotbar(@Nullable Player player) {
        if (!isActive(player)) return true;
        if (activePlacement != null) return true;
        return hudMaster && hudHotbar;
    }

    public static boolean shouldShowNotifications(@Nullable Player player) {
        if (!isActive(player)) return true;
        return hudMaster && hudNotifications;
    }

    public static boolean isFavorite(String key) {
        synchronized (FAVORITE_KEYS) {
            return FAVORITE_KEYS.contains(key);
        }
    }

    public static boolean toggleFavorite(String key) {
        synchronized (FAVORITE_KEYS) {
            if (!FAVORITE_KEYS.add(key)) {
                FAVORITE_KEYS.remove(key);
                return false;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ telemetry & event history

    public static void recordEvent(String description) {
        if (description == null || description.isEmpty()) return;
        String entry = "[" + EVENT_TIME_FMT.format(Instant.now()) + "] " + description;
        synchronized (RECENT_EVENTS) {
            if (RECENT_EVENTS.size() >= MAX_EVENTS) {
                RECENT_EVENTS.pollFirst();
            }
            RECENT_EVENTS.addLast(entry);
        }
    }

    public static List<String> recentEventsSnapshot() {
        synchronized (RECENT_EVENTS) {
            return new ArrayList<>(RECENT_EVENTS);
        }
    }

    public static void recordFrameTick() {
        long now = System.nanoTime();
        if (lastFrameTimestampNs != 0L) {
            long dt = now - lastFrameTimestampNs;
            if (dt > 100_000L && dt < 5_000_000_000L) {
                synchronized (FRAME_TIMES_NS) {
                    FRAME_TIMES_NS[frameRingWriteIdx] = dt;
                    frameRingWriteIdx = (frameRingWriteIdx + 1) % FRAME_RING_SIZE;
                    if (frameRingCount < FRAME_RING_SIZE) frameRingCount++;
                }
            }
        }
        lastFrameTimestampNs = now;
    }

    /** Returns {@code [fps, avgMs, minMs, maxMs, p50Ms, p99Ms, onePercentLowFps]}. */
    public static double[] computeFrameStats() {
        long[] copy;
        int n;
        synchronized (FRAME_TIMES_NS) {
            n = frameRingCount;
            if (n == 0) return new double[]{60.0, 16.6, 16.6, 16.6, 16.6, 16.6, 60.0};
            copy = new long[n];
            for (int i = 0; i < n; i++) {
                int idx = Math.floorMod(frameRingWriteIdx - 1 - i, FRAME_RING_SIZE);
                copy[i] = FRAME_TIMES_NS[idx];
            }
        }
        long sum = 0L;
        long min = Long.MAX_VALUE;
        long max = 0L;
        for (long dt : copy) {
            sum += dt;
            if (dt < min) min = dt;
            if (dt > max) max = dt;
        }
        Arrays.sort(copy);
        double avgMs = (sum / (double) n) / 1_000_000.0;
        double fps = avgMs > 0.001 ? 1000.0 / avgMs : 0.0;
        double minMs = min / 1_000_000.0;
        double maxMs = max / 1_000_000.0;
        double p50Ms = copy[n / 2] / 1_000_000.0;
        double p99Ms = copy[Math.min(n - 1, (int) Math.floor(n * 0.99))] / 1_000_000.0;
        double onePctLowFps = p99Ms > 0.001 ? 1000.0 / p99Ms : 0.0;
        return new double[]{fps, avgMs, minMs, maxMs, p50Ms, p99Ms, onePctLowFps};
    }
}
