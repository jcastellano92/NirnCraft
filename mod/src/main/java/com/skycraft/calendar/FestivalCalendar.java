package com.skycraft.calendar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.quest.Objective;
import com.skycraft.quest.Quest;
import com.skycraft.quest.Quests;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tamrielic festival calendar (MS16).
 *
 * <p>Maps Minecraft world days ({@code level.getDayTime() / 24000L}) onto Tamriel's 12 months
 * (30 days per month, 360 days per year) and activates regional hold festivals loaded from
 * {@code /skycraft/calendar/festivals.json}. When a festival is active in a settlement, a
 * non-destructive temporary plaza overlay (lanterns, hold banners, campfire brazier) is placed
 * in air blocks around {@link AnchorType#MEETING} and cleaned up automatically when the festival
 * ends.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class FestivalCalendar {
    public static final int DAYS_PER_MONTH = 30;
    public static final int MONTHS_PER_YEAR = 12;
    public static final int DAYS_PER_YEAR = DAYS_PER_MONTH * MONTHS_PER_YEAR;
    private static final long TICKS_PER_DAY = 24000L;

    public static final List<String> SKYRIM_HOLDS = List.of(
            "whiterun", "haafingar", "eastmarch", "the_rift", "the_reach",
            "falkreath", "hjaalmarch", "the_pale", "winterhold"
    );

    public record TamrielMonth(int index, String id, String name, String langKey) {
        public Component displayName() {
            return Component.translatable(langKey);
        }
    }

    public record FestivalOverlay(String bannerColor, String lanterns, boolean bonfire, List<String> stalls) {}

    public record Festival(
            String id,
            String name,
            String titleKey,
            String announceKey,
            int month,
            String monthId,
            String monthName,
            int day,
            int dayOfYear,
            int durationDays,
            String province,
            String region,
            String settlement,
            String purpose,
            List<String> needs,
            List<String> why,
            String festivalQuest,
            FestivalOverlay overlay
    ) {
        public Component titleComponent() {
            return Component.translatable(titleKey);
        }

        public Component announceComponent() {
            return Component.translatable(announceKey);
        }

        public boolean isActiveOnDay(int queryDayOfYear) {
            if (queryDayOfYear < 1 || queryDayOfYear > DAYS_PER_YEAR) return false;
            for (int d = 0; d < Math.max(1, durationDays); d++) {
                int activeDay = ((dayOfYear - 1 + d) % DAYS_PER_YEAR) + 1;
                if (activeDay == queryDayOfYear) return true;
            }
            return false;
        }

        public boolean matchesRegion(@Nullable String regionId) {
            if (regionId == null || regionId.isEmpty()) return true;
            String shortQuery = shortHoldId(regionId);
            return region.equalsIgnoreCase(regionId) || shortHoldId(region).equalsIgnoreCase(shortQuery);
        }
    }

    public record TamrielDate(int year, int month, TamrielMonth monthInfo, int dayOfMonth, int dayOfYear) {
        public Component formatComponent() {
            return Component.translatable(
                    "skycraft.calendar.date.format",
                    dayOfMonth,
                    monthInfo.displayName(),
                    year,
                    dayOfYear
            );
        }
    }

    private record ActiveOverlay(String festivalId, ResourceKey<Level> dimension, BlockPos center, List<BlockPos> placedBlocks, long expiresGameTime) {}

    private static final List<TamrielMonth> MONTHS;
    private static final List<Festival> FESTIVALS;
    private static final Map<String, Festival> BY_ID;

    /** Manual overrides triggered via {@code /skycraft-calendar trigger <id>}: festivalId -> expiration gameTime. */
    private static final Map<String, Long> FORCED_UNTIL = new ConcurrentHashMap<>();
    /** Currently placed non-destructive block overlays keyed by festival id. */
    private static final Map<String, ActiveOverlay> ACTIVE_OVERLAYS = new ConcurrentHashMap<>();
    /** Tracks the last festival announcement sent to each player (`playerUUID -> festivalId + ":" + dayOfYear`). */
    private static final Map<UUID, String> ANNOUNCED = new ConcurrentHashMap<>();

    static {
        List<TamrielMonth> months = new ArrayList<>();
        List<Festival> festivals = new ArrayList<>();
        Map<String, Festival> byId = new LinkedHashMap<>();
        try (InputStream in = FestivalCalendar.class.getResourceAsStream("/skycraft/calendar/festivals.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("calendar") && root.get("calendar").isJsonObject()) {
                    JsonObject cal = root.getAsJsonObject("calendar");
                    if (cal.has("months") && cal.get("months").isJsonArray()) {
                        for (JsonElement el : cal.getAsJsonArray("months")) {
                            JsonObject m = el.getAsJsonObject();
                            months.add(new TamrielMonth(
                                    m.get("index").getAsInt(),
                                    m.get("id").getAsString(),
                                    m.get("name").getAsString(),
                                    m.get("langKey").getAsString()
                            ));
                        }
                    }
                }
                if (root.has("festivals") && root.get("festivals").isJsonArray()) {
                    for (JsonElement el : root.getAsJsonArray("festivals")) {
                        Festival f = parseFestival(el.getAsJsonObject());
                        festivals.add(f);
                        byId.put(f.id(), f);
                    }
                }
            } else {
                Skycraft.LOGGER.warn("Missing /skycraft/calendar/festivals.json");
            }
        } catch (Exception ex) {
            Skycraft.LOGGER.error("Failed to load /skycraft/calendar/festivals.json", ex);
        }
        MONTHS = Collections.unmodifiableList(months);
        FESTIVALS = Collections.unmodifiableList(festivals);
        BY_ID = Collections.unmodifiableMap(byId);
        Skycraft.LOGGER.info("Loaded {} Tamrielic festivals across {} months", FESTIVALS.size(), MONTHS.size());
    }

    private FestivalCalendar() {}

    private static Festival parseFestival(JsonObject o) {
        String id = str(o, "id", "");
        String name = str(o, "name", id);
        String titleKey = str(o, "titleKey", "skycraft.calendar.festival." + id + ".title");
        String announceKey = str(o, "announceKey", "skycraft.calendar.festival." + id + ".announce");
        int month = o.has("month") ? o.get("month").getAsInt() : 1;
        String monthId = str(o, "monthId", "morning_star");
        String monthName = str(o, "monthName", "Morning Star");
        int day = o.has("day") ? o.get("day").getAsInt() : 1;
        int dayOfYear = o.has("dayOfYear") ? o.get("dayOfYear").getAsInt() : ((month - 1) * DAYS_PER_MONTH + day);
        int durationDays = o.has("durationDays") ? o.get("durationDays").getAsInt() : 1;
        String province = str(o, "province", "skyrim");
        String region = str(o, "region", "whiterun");
        String settlement = str(o, "settlement", "whiterun");
        String purpose = str(o, "purpose", "");
        List<String> needs = strList(o, "needs");
        List<String> why = strList(o, "why");
        String festivalQuest = str(o, "festivalQuest", "radiant_favor");

        FestivalOverlay overlay = new FestivalOverlay("yellow", "lantern", false, List.of());
        if (o.has("overlay") && o.get("overlay").isJsonObject()) {
            JsonObject ov = o.getAsJsonObject("overlay");
            overlay = new FestivalOverlay(
                    str(ov, "bannerColor", "yellow"),
                    str(ov, "lanterns", "lantern"),
                    ov.has("bonfire") && ov.get("bonfire").getAsBoolean(),
                    strList(ov, "stalls")
            );
        }
        return new Festival(
                id, name, titleKey, announceKey, month, monthId, monthName,
                day, dayOfYear, durationDays, province, region, settlement,
                purpose, needs, why, festivalQuest, overlay
        );
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static List<String> strList(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return List.of();
        JsonArray arr = o.getAsJsonArray(key);
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) out.add(e.getAsString());
        return List.copyOf(out);
    }

    public static String shortHoldId(String regionId) {
        int slash = regionId.indexOf('/');
        return slash >= 0 ? regionId.substring(slash + 1) : regionId;
    }

    // ------------------------------------------------------------------ date math

    public static List<TamrielMonth> months() {
        return MONTHS;
    }

    public static List<Festival> all() {
        return FESTIVALS;
    }

    public static Optional<Festival> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static TamrielDate dateAt(ServerLevel level) {
        return dateFromDayTime(level.getDayTime());
    }

    public static TamrielDate dateFromDayTime(long dayTime) {
        long totalDays = Math.max(0L, dayTime / TICKS_PER_DAY);
        int year = (int) (totalDays / DAYS_PER_YEAR) + 201; // 4E 201 baseline
        int dayOfYear = (int) (totalDays % DAYS_PER_YEAR) + 1;
        int monthIndex = ((dayOfYear - 1) / DAYS_PER_MONTH) + 1;
        int dayOfMonth = ((dayOfYear - 1) % DAYS_PER_MONTH) + 1;
        TamrielMonth monthInfo = monthByIndex(monthIndex);
        return new TamrielDate(year, monthIndex, monthInfo, dayOfMonth, dayOfYear);
    }

    public static TamrielMonth monthByIndex(int index) {
        for (TamrielMonth m : MONTHS) {
            if (m.index() == index) return m;
        }
        return new TamrielMonth(1, "morning_star", "Morning Star", "skycraft.calendar.month.morning_star");
    }

    /** Active festivals right now (either by calendar date or manual command trigger). */
    public static List<Festival> activeFestivals(ServerLevel level, @Nullable String regionFilter) {
        TamrielDate date = dateAt(level);
        long now = level.getGameTime();
        List<Festival> out = new ArrayList<>();
        for (Festival f : FESTIVALS) {
            if (!f.matchesRegion(regionFilter)) continue;
            Long forcedUntil = FORCED_UNTIL.get(f.id());
            boolean forced = forcedUntil != null && forcedUntil > now;
            if (forced || f.isActiveOnDay(date.dayOfYear())) {
                out.add(f);
            }
        }
        return out;
    }

    /** Triggers a festival immediately for one full Minecraft day (24,000 ticks) and places its plaza overlay. */
    public static boolean triggerFestival(ServerLevel level, String festivalId, @Nullable ServerPlayer initiator) {
        Festival f = BY_ID.get(festivalId);
        if (f == null) return false;
        long until = level.getGameTime() + TICKS_PER_DAY * Math.max(1, f.durationDays());
        FORCED_UNTIL.put(f.id(), until);

        BlockPos plaza = findPlazaCenter(level, f, initiator);
        if (plaza != null) {
            placeOverlay(level, f, plaza, until);
        }
        if (initiator != null) {
            announceFestival(initiator, f);
            offerFestivalQuest(initiator, f);
        }
        return true;
    }

    @Nullable
    public static BlockPos findPlazaCenter(ServerLevel level, Festival f, @Nullable ServerPlayer fallbackPlayer) {
        Optional<SettlementAnchors.Entry> entryOpt = SettlementAnchors.find(level, f.settlement());
        if (entryOpt.isPresent()) {
            SettlementAnchors.Entry entry = entryOpt.get();
            Anchor meeting = entry.first(AnchorType.MEETING);
            if (meeting != null) return meeting.pos();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, entry.x, entry.z);
            return new BlockPos(entry.x, y, entry.z);
        }
        Optional<Place> placeOpt = Places.byId(f.settlement());
        if (placeOpt.isPresent()) {
            Place p = placeOpt.get();
            if (level.hasChunk(p.x() >> 4, p.z() >> 4)) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.x(), p.z());
                return new BlockPos(p.x(), y, p.z());
            }
        }
        if (fallbackPlayer != null) {
            BlockPos pPos = fallbackPlayer.blockPosition();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pPos.getX(), pPos.getZ());
            return new BlockPos(pPos.getX(), y, pPos.getZ());
        }
        return null;
    }

    // ------------------------------------------------------------------ non-destructive overlay

    private static void placeOverlay(ServerLevel level, Festival f, BlockPos center, long expiresGameTime) {
        removeOverlay(level.getServer(), f.id());
        List<BlockPos> placed = new ArrayList<>();
        Block lanternBlock = "soul_lantern".equalsIgnoreCase(f.overlay().lanterns())
                ? Blocks.SOUL_LANTERN
                : Blocks.LANTERN;
        Block bannerBlock = bannerForColor(f.overlay().bannerColor());

        int[][] offsets = {
                {4, 0}, {-4, 0}, {0, 4}, {0, -4},
                {3, 3}, {-3, 3}, {3, -3}, {-3, -3}
        };
        for (int i = 0; i < offsets.length; i++) {
            int bx = center.getX() + offsets[i][0];
            int bz = center.getZ() + offsets[i][1];
            if (!level.hasChunk(bx >> 4, bz >> 4)) continue;
            int by = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            BlockPos basePos = new BlockPos(bx, by, bz);
            BlockPos groundPos = basePos.below();
            if (!level.getBlockState(groundPos).isSolidRender(level, groundPos)) continue;
            if (!level.getBlockState(basePos).isAir()) continue;

            if (i < 4) {
                BlockPos topPos = basePos.above();
                if (!level.getBlockState(topPos).isAir()) continue;
                level.setBlock(basePos, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                level.setBlock(topPos, lanternBlock.defaultBlockState(), 3);
                placed.add(topPos);
                placed.add(basePos);
            } else {
                level.setBlock(basePos, bannerBlock.defaultBlockState(), 3);
                placed.add(basePos);
            }
        }

        if (f.overlay().bonfire()) {
            BlockPos firePos = new BlockPos(center.getX() + 2,
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX() + 2, center.getZ()),
                    center.getZ());
            if (level.hasChunk(firePos.getX() >> 4, firePos.getZ() >> 4)
                    && level.getBlockState(firePos).isAir()
                    && level.getBlockState(firePos.below()).isSolidRender(level, firePos.below())) {
                level.setBlock(firePos, Blocks.CAMPFIRE.defaultBlockState(), 3);
                placed.add(firePos);
            }
        }

        if (!placed.isEmpty()) {
            ACTIVE_OVERLAYS.put(f.id(), new ActiveOverlay(f.id(), level.dimension(), center, List.copyOf(placed), expiresGameTime));
        }
    }

    public static void removeOverlay(@Nullable net.minecraft.server.MinecraftServer server, String festivalId) {
        ActiveOverlay ov = ACTIVE_OVERLAYS.remove(festivalId);
        if (ov == null || server == null) return;
        ServerLevel level = server.getLevel(ov.dimension());
        if (level == null) return;
        for (BlockPos pos : ov.placedBlocks()) {
            if (level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static Block bannerForColor(String color) {
        return switch (color.toLowerCase(Locale.ROOT)) {
            case "white" -> Blocks.WHITE_BANNER;
            case "orange" -> Blocks.ORANGE_BANNER;
            case "magenta" -> Blocks.MAGENTA_BANNER;
            case "light_blue" -> Blocks.LIGHT_BLUE_BANNER;
            case "yellow" -> Blocks.YELLOW_BANNER;
            case "lime" -> Blocks.LIME_BANNER;
            case "pink" -> Blocks.PINK_BANNER;
            case "cyan" -> Blocks.CYAN_BANNER;
            case "purple" -> Blocks.PURPLE_BANNER;
            case "blue" -> Blocks.BLUE_BANNER;
            case "green" -> Blocks.GREEN_BANNER;
            case "red" -> Blocks.RED_BANNER;
            default -> Blocks.LIGHT_GRAY_BANNER;
        };
    }

    // ------------------------------------------------------------------ announcements & quest hooks

    public static void announceFestival(ServerPlayer player, Festival f) {
        Component header = Component.translatable("skycraft.calendar.festival.banner", f.titleComponent())
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        Component body = f.announceComponent().copy().withStyle(ChatFormatting.YELLOW);
        player.displayClientMessage(header, false);
        player.displayClientMessage(body, false);
        player.displayClientMessage(header, true);
        player.level().playSound(
                null,
                player.blockPosition(),
                SoundEvents.BELL_BLOCK,
                SoundSource.AMBIENT,
                0.9f,
                1.1f
        );
    }

    public static boolean offerFestivalQuest(ServerPlayer player, Festival f) {
        if (f.festivalQuest() == null || f.festivalQuest().isEmpty()) return false;
        String qid = "festival_" + f.id();
        if (Quests.find(player.server, player.getUUID(), qid) != null) return false;
        if (!Quests.canTakeMore(player)) return false;

        Region reg = Regions.at(player.serverLevel(), player.blockPosition());
        Quest q = new Quest();
        q.id = qid;
        q.kind = f.festivalQuest();
        q.title = Component.translatable("skycraft.calendar.quest.title", f.titleComponent());
        q.giverName = f.titleComponent();
        q.hold = reg != null ? reg.id() : ("skyrim/" + f.region());
        int recLevel = reg != null ? Math.max(1, reg.minLevel()) : 1;
        q.rewardGold = 60L + recLevel * 10L;
        q.extra.putInt("festival_xp", 90 + recLevel * 15);

        Objective obj = new Objective();
        obj.type = Objective.Type.TALK_TO;
        obj.target = "any";
        obj.required = 1;
        obj.text = Component.translatable("skycraft.calendar.quest.objective", f.titleComponent());
        q.objectives.add(obj);

        boolean started = Quests.start(player, q);
        if (started) {
            player.sendSystemMessage(
                    Component.translatable("skycraft.calendar.quest.started", f.titleComponent())
                            .withStyle(ChatFormatting.GOLD)
            );
        }
        return started;
    }

    // ------------------------------------------------------------------ Forge events

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        long gameTime = level.getGameTime();
        if (gameTime % 200L != 43L) return;

        // Clean up expired manual triggers and overlays
        FORCED_UNTIL.entrySet().removeIf(e -> e.getValue() <= gameTime);
        List<Festival> active = activeFestivals(level, null);
        Set<String> activeIds = new java.util.HashSet<>();
        for (Festival f : active) activeIds.add(f.id());

        for (String ovId : new ArrayList<>(ACTIVE_OVERLAYS.keySet())) {
            ActiveOverlay ov = ACTIVE_OVERLAYS.get(ovId);
            if (ov != null && (gameTime >= ov.expiresGameTime() || !activeIds.contains(ovId))) {
                removeOverlay(level.getServer(), ovId);
            }
        }

        if (active.isEmpty() || level.players().isEmpty()) return;
        TamrielDate today = dateAt(level);

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            Region reg = Regions.at(level, player.blockPosition());
            String holdShort = shortHoldId(reg.id());
            for (Festival f : active) {
                if (!f.matchesRegion(holdShort)) continue;
                BlockPos plaza = findPlazaCenter(level, f, null);
                if (plaza != null && player.blockPosition().distSqr(plaza) <= 96.0 * 96.0) {
                    if (!ACTIVE_OVERLAYS.containsKey(f.id())) {
                        placeOverlay(level, f, plaza, gameTime + TICKS_PER_DAY);
                    }
                    String key = f.id() + ":" + today.dayOfYear();
                    if (!key.equals(ANNOUNCED.get(player.getUUID()))) {
                        ANNOUNCED.put(player.getUUID(), key);
                        announceFestival(player, f);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof NpcEntity)) return;
        if (!player.isShiftKeyDown()) return;

        ServerLevel level = player.serverLevel();
        Region reg = Regions.at(level, player.blockPosition());
        List<Festival> active = activeFestivals(level, shortHoldId(reg.id()));
        if (active.isEmpty()) return;
        Festival f = active.get(0);
        if (offerFestivalQuest(player, f)) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ self-test

    public record SelfTestResult(boolean ok, int monthCount, int festivalCount, Map<String, Integer> holdCounts, List<String> errors) {}

    public static SelfTestResult selftest() {
        List<String> errors = new ArrayList<>();
        if (MONTHS.size() != MONTHS_PER_YEAR) {
            errors.add("Expected 12 Tamrielic months, found " + MONTHS.size());
        }
        if (FESTIVALS.isEmpty()) {
            errors.add("No festivals loaded from /skycraft/calendar/festivals.json");
        }

        Map<String, Integer> holdCounts = new LinkedHashMap<>();
        for (String h : SKYRIM_HOLDS) holdCounts.put(h, 0);

        for (Festival f : FESTIVALS) {
            if (f.id().isEmpty()) errors.add("Festival with empty id");
            if (f.month() < 1 || f.month() > 12) errors.add(f.id() + ": invalid month " + f.month());
            if (f.day() < 1 || f.day() > 30) errors.add(f.id() + ": invalid day " + f.day());
            if (f.dayOfYear() < 1 || f.dayOfYear() > 360) errors.add(f.id() + ": invalid dayOfYear " + f.dayOfYear());
            if (f.purpose().isBlank()) errors.add(f.id() + ": missing purpose");
            if (f.needs().isEmpty()) errors.add(f.id() + ": missing needs");
            if (f.why().isEmpty()) errors.add(f.id() + ": missing why");
            String h = shortHoldId(f.region());
            if (holdCounts.containsKey(h)) {
                holdCounts.put(h, holdCounts.get(h) + 1);
            }
        }

        for (String h : SKYRIM_HOLDS) {
            if (holdCounts.getOrDefault(h, 0) < 1) {
                errors.add("Skyrim hold " + h + " has no festivals in festivals.json");
            }
        }

        return new SelfTestResult(errors.isEmpty(), MONTHS.size(), FESTIVALS.size(), holdCounts, errors);
    }
}
