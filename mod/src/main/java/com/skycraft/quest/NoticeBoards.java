package com.skycraft.quest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skycraft.Skycraft;
import com.skycraft.core.Holds;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.dungeons.world.Identity;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.world.LocationKind;
import com.skycraft.world.WorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util. EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Notice boards in every town, city, capital and roadside inn (MS12 — {@code F-BOARD}, R3d-2.1).
 *
 * <p>Each board holds 3 to 6 live jobs generated deterministically from the settlement's seed and the current
 * 2-day epoch ({@value #REFRESH_TICKS} ticks = 2 in-game days). Every job targets a real, uncleared dungeon or site
 * {@link Place} ({@link PlaceType#DUNGEON_FIXED}, {@link PlaceType#FORT}, {@link PlaceType#DAEDRIC_SHRINE},
 * {@link PlaceType#DRAGON_MOUND}, {@link PlaceType#WORD_WALL}) 300 to 1,500 blocks from the settlement across five
 * archetypes:</p>
 * <ul>
 *     <li><b>bandit_bounty</b>: kill the bandit chief at {@code <Place>}.</li>
 *     <li><b>beast_hunt</b>: slay the {@code <creature>} lairing near {@code <Place>}.</li>
 *     <li><b>lost_heirloom</b>: recover {@code <item>} from {@code <Place>} and return it to {@code <resident>} in {@code <settlement>}.</li>
 *     <li><b>missing_person</b>: find news of {@code <name>} last seen heading toward {@code <Place>}.</li>
 *     <li><b>supply_order</b>: gather {@code <count> <ingredient/ore>} near {@code <Place>} and deliver to the settlement's alchemist, smith or innkeeper.</li>
 * </ul>
 * <p>Accepting a job creates a tracked {@link Quest} in {@link Quests}, marks the target {@link Place} on the
 * player's map and compass, and pays a gold + XP/reputation reward scaled by the target dungeon's {@link Size} tier
 * ({@code SMALL -> 100g}, {@code MEDIUM -> 200g}, {@code LARGE -> 350g}, {@code MAJOR -> 500g}), shared by the
 * player's party.</p>
 */
public final class NoticeBoards {
    /** Boards refresh every 2 in-game days (48,000 ticks). */
    public static final long REFRESH_TICKS = 48_000L;
    public static final int MIN_JOBS = 3;
    public static final int MAX_JOBS = 6;
    public static final int MIN_DISTANCE = 300;
    public static final int MAX_DISTANCE = 1500;

    public static final Set<PlaceType> TARGET_TYPES = EnumSet.of(
            PlaceType.DUNGEON_FIXED, PlaceType.FORT, PlaceType.DAEDRIC_SHRINE, PlaceType.DRAGON_MOUND, PlaceType.WORD_WALL);

    public enum Size {
        SMALL(100L, 50, 2),
        MEDIUM(200L, 100, 4),
        LARGE(350L, 175, 6),
        MAJOR(500L, 250, 10);

        public final long gold;
        public final int xp;
        public final int reputation;

        Size(long gold, int xp, int reputation) {
            this.gold = gold;
            this.xp = xp;
            this.reputation = reputation;
        }
    }

    public record Job(
            int index,
            String id,
            String archetype,
            Quest.Category category,
            Place settlement,
            Place targetPlace,
            BlockPos targetPos,
            int distance,
            Size size,
            long rewardGold,
            int rewardXp,
            int rewardRep,
            Component title,
            Component summary,
            Component description,
            String targetEntity,
            String targetEntityName,
            String targetItem,
            String targetItemName,
            int targetCount,
            String personName
    ) {
    }

    private record SupplySpec(String item, String name, int count, String recipient) {}
    private record BeastSpec(String entity, String fallback, String name, float hp, float dmg) {}
    private record BossSpec(String entity, String name, float hp, float dmg, String weapon, String armor) {}

    private static final List<BossSpec> BOSS_POOL = new ArrayList<>();
    private static final Map<String, List<BeastSpec>> BEASTS_BY_PROVINCE = new LinkedHashMap<>();
    private static final List<String> HEIRLOOM_ITEMS = new ArrayList<>();
    private static final List<String> HEIRLOOM_RESIDENTS = new ArrayList<>();
    private static final List<String> MISSING_NAMES = new ArrayList<>();
    private static final List<SupplySpec> SUPPLIES = new ArrayList<>();
    private static boolean templatesLoaded;

    private NoticeBoards() {}

    // ------------------------------------------------------------------ template loading

    public static synchronized void ensureLoaded() {
        if (templatesLoaded) return;
        templatesLoaded = true;
        try (InputStream in = NoticeBoards.class.getResourceAsStream("/skycraft/quests/boards/board_templates.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray archs = root.getAsJsonArray("archetypes");
                if (archs != null) {
                    for (JsonElement el : archs) {
                        JsonObject a = el.getAsJsonObject();
                        String id = a.get("id").getAsString();
                        switch (id) {
                            case "bandit_bounty" -> {
                                for (JsonElement b : a.getAsJsonArray("boss_types")) {
                                    JsonObject bo = b.getAsJsonObject();
                                    BOSS_POOL.add(new BossSpec(bo.get("entity").getAsString(), bo.get("name").getAsString(),
                                            bo.get("hp").getAsFloat(), bo.get("dmg").getAsFloat(),
                                            bo.get("weapon").getAsString(), bo.get("armor").getAsString()));
                                }
                            }
                            case "beast_hunt" -> {
                                JsonObject byProv = a.getAsJsonObject("beasts_by_province");
                                for (Map.Entry<String, JsonElement> pe : byProv.entrySet()) {
                                    List<BeastSpec> list = new ArrayList<>();
                                    for (JsonElement b : pe.getValue().getAsJsonArray()) {
                                        JsonObject bo = b.getAsJsonObject();
                                        list.add(new BeastSpec(bo.get("entity").getAsString(), bo.get("fallback").getAsString(),
                                                bo.get("name").getAsString(), bo.get("hp").getAsFloat(), bo.get("dmg").getAsFloat()));
                                    }
                                    BEASTS_BY_PROVINCE.put(pe.getKey(), list);
                                }
                            }
                            case "lost_heirloom" -> {
                                for (JsonElement it : a.getAsJsonArray("items")) HEIRLOOM_ITEMS.add(it.getAsString());
                                for (JsonElement r : a.getAsJsonArray("residents")) HEIRLOOM_RESIDENTS.add(r.getAsString());
                            }
                            case "missing_person" -> {
                                for (JsonElement n : a.getAsJsonArray("names")) MISSING_NAMES.add(n.getAsString());
                            }
                            case "supply_order" -> {
                                for (JsonElement s : a.getAsJsonArray("supplies")) {
                                    JsonObject so = s.getAsJsonObject();
                                    SUPPLIES.add(new SupplySpec(so.get("item").getAsString(), so.get("name").getAsString(),
                                            so.get("count").getAsInt(), so.get("recipient").getAsString()));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Could not load notice board templates, using defaults", e);
        }
        if (BOSS_POOL.isEmpty()) {
            BOSS_POOL.add(new BossSpec("minecraft:pillager", "Bandit Chief", 3.0f, 1.6f, "skycraft:steel_greatsword", "iron"));
        }
        if (BEASTS_BY_PROVINCE.isEmpty()) {
            BEASTS_BY_PROVINCE.put("skyrim", List.of(
                    new BeastSpec("skycraft:troll", "minecraft:polar_bear", "Frost Troll", 3.5f, 1.8f),
                    new BeastSpec("skycraft:sabre_cat", "minecraft:ravager", "Snowy Sabre Cat", 3.0f, 1.7f)));
        }
        if (HEIRLOOM_ITEMS.isEmpty()) HEIRLOOM_ITEMS.add("Family Signet Ring");
        if (HEIRLOOM_RESIDENTS.isEmpty()) HEIRLOOM_RESIDENTS.add("the local smith");
        if (MISSING_NAMES.isEmpty()) MISSING_NAMES.add("Halvar the Trapper");
        if (SUPPLIES.isEmpty()) {
            SUPPLIES.add(new SupplySpec("minecraft:iron_ingot", "Iron Ingots", 5, "the local smith"));
        }
    }

    // ------------------------------------------------------------------ settlement & sign lookup

    /** True if {@code place} is a settlement or roadside inn that hosts a notice board. */
    public static boolean hasBoard(Place place) {
        if (place == null) return false;
        if (place.type() == PlaceType.TOWN || place.type() == PlaceType.CITY || place.type() == PlaceType.CAPITAL) return true;
        if (place.type() == PlaceType.VILLAGE) return true;
        return isRoadsideInn(place);
    }

    /** Alias used by client/command callers. */
    public static boolean hasNoticeBoard(@Nullable Place place) {
        return hasBoard(place);
    }

    /** True if {@code place} is a roadside inn or waystation hosting a board. */
    public static boolean isRoadsideInn(@Nullable Place place) {
        if (place == null) return false;
        String id = place.id().toLowerCase(Locale.ROOT);
        String v = place.variant() == null ? "" : place.variant().toLowerCase(Locale.ROOT);
        return v.contains("inn") || id.endsWith("_inn") || id.contains("nightgate") || id.contains("old_hroldan")
                || id.contains("raid_inn") || id.contains("crossroads_inn");
    }

    /** Finds a settlement by id or display name, or the nearest settlement to {@code fallbackPlayer} when blank. */
    public static Optional<Place> resolveSettlement(String idOrName, @Nullable ServerPlayer fallbackPlayer) {
        if (idOrName != null && !idOrName.isBlank()) {
            String q = idOrName.trim().toLowerCase(Locale.ROOT);
            Optional<Place> exact = Places.byId(q);
            if (exact.isPresent() && hasBoard(exact.get())) return exact;
            for (Place p : Places.all()) {
                if (!hasBoard(p)) continue;
                if (p.id().equalsIgnoreCase(q) || p.name().getString().equalsIgnoreCase(q)) return Optional.of(p);
            }
            for (Place p : Places.all()) {
                if (!hasBoard(p)) continue;
                if (p.id().toLowerCase(Locale.ROOT).contains(q) || p.name().getString().toLowerCase(Locale.ROOT).contains(q)) {
                    return Optional.of(p);
                }
            }
            return Optional.empty();
        }
        if (fallbackPlayer != null) {
            return nearestBoardSettlement(fallbackPlayer.level(), fallbackPlayer.blockPosition(), 512);
        }
        return Optional.empty();
    }

    /** Nearest settlement or roadside inn within {@code maxRadius} blocks of {@code pos}. */
    public static Optional<Place> nearestBoardSettlement(Level level, BlockPos pos, int maxRadius) {
        if (level.dimension() != Level.OVERWORLD) return Optional.empty();
        Place best = null;
        long bestDistSq = (long) maxRadius * maxRadius;
        for (Place p : Places.overlapping(pos.getX() - maxRadius, pos.getZ() - maxRadius, pos.getX() + maxRadius, pos.getZ() + maxRadius)) {
            if (!hasBoard(p)) continue;
            long d2 = p.distSqr(pos.getX(), pos.getZ());
            if (d2 <= bestDistSq) {
                bestDistSq = d2;
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }

    /** True if {@code sign} is a settlement or inn Notice Board sign. */
    public static boolean isNoticeBoardSign(Level level, BlockPos pos, SignBlockEntity sign) {
        if (level.dimension() != Level.OVERWORLD) return false;
        if (matchesBoardText(sign.getFrontText()) || matchesBoardText(sign.getBackText())) {
            return nearestBoardSettlement(level, pos, 256).isPresent();
        }
        return false;
    }

    private static boolean matchesBoardText(SignText text) {
        for (int i = 0; i < 4; i++) {
            Component msg = text.getMessage(i, false);
            String raw = msg.getString().toLowerCase(Locale.ROOT);
            String str = msg.toString().toLowerCase(Locale.ROOT);
            if (str.contains("settlement.skycraft.notice.title")
                    || str.contains("settlement.skycraft.notice.line")
                    || str.contains("quest.skycraft.board.prompt")
                    || raw.contains("notice board")
                    || raw.contains("bounties & work")
                    || raw.contains("[contracts]")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ deterministic job generation

    public static long currentEpoch(@Nullable MinecraftServer server) {
        if (server == null || server.overworld() == null) return 0L;
        return Math.max(0L, server.overworld().getDayTime() / REFRESH_TICKS);
    }

    /** Determines the dungeon {@link Size} tier of a target {@link Place}. */
    public static Size sizeOf(Place place, int jobSlot) {
        String v = (place.variant() == null ? "" : place.variant()).toLowerCase(Locale.ROOT);
        if (place.radius() >= 54 || place.type() == PlaceType.DAEDRIC_SHRINE
                || v.contains("dwemer") || v.contains("citadel") || v.contains("sanctum") || v.contains("labyrinth")) {
            return Size.MAJOR;
        }
        if (place.radius() >= 44 || place.type() == PlaceType.FORT || v.contains("barrow") || v.contains("tomb") || v.contains("ruin")) {
            return (Math.floorMod(place.id().hashCode() + jobSlot, 4) == 0) ? Size.MAJOR : Size.LARGE;
        }
        if (place.type() == PlaceType.WORD_WALL || place.type() == PlaceType.DRAGON_MOUND || place.radius() <= 28
                || v.contains("camp") || v.contains("mine") || v.contains("den")) {
            return Size.SMALL;
        }
        int mod = Math.floorMod(place.id().hashCode() + jobSlot * 7, 4);
        return switch (mod) {
            case 0 -> Size.SMALL;
            case 1, 2 -> Size.MEDIUM;
            default -> Size.LARGE;
        };
    }

    /**
     * Generates the 3 to 6 live jobs currently posted on {@code settlement}'s notice board.
     * Filters out places that {@code viewer} has already cleared when uncleared alternatives exist.
     */
    public static List<Job> jobsFor(@Nullable MinecraftServer server, Place settlement, @Nullable ServerPlayer viewer) {
        ensureLoaded();
        long epoch = currentEpoch(server);
        long worldSeed = server != null && server.overworld() != null ? server.overworld().getSeed() : 1013904223L;
        long seed = mix(worldSeed ^ settlement.id().hashCode(), epoch, 0x424F415244L);

        int count = MIN_JOBS + (int) Math.floorMod(mix(seed, 1, 17), (MAX_JOBS - MIN_JOBS + 1));
        List<Place> candidates = candidateTargets(settlement, viewer);
        if (candidates.isEmpty()) return List.of();

        String province = provinceOf(settlement);
        List<BeastSpec> beasts = BEASTS_BY_PROVINCE.getOrDefault(province, BEASTS_BY_PROVINCE.getOrDefault("skyrim", List.of()));
        String[] archetypes = {"bandit_bounty", "beast_hunt", "lost_heirloom", "missing_person", "supply_order"};

        List<Job> out = new ArrayList<>(count);
        int startOffset = (int) Math.floorMod(mix(seed, 2, 31), candidates.size());
        int archOffset = (int) Math.floorMod(mix(seed, 3, 53), archetypes.length);

        for (int i = 0; i < count; i++) {
            Place target = candidates.get((startOffset + i * 3) % candidates.size());
            String arch = archetypes[(archOffset + i) % archetypes.length];
            long jobSeed = mix(seed, i + 10, target.id().hashCode());

            // Compute objective position and clamp distance to [300, 1500] when needed (e.g. remote island towns)
            double dx = target.x() - settlement.x();
            double dz = target.z() - settlement.z();
            double rawDist = Math.hypot(dx, dz);
            int tx = target.x();
            int tz = target.z();
            int dist = (int) Math.round(rawDist);
            if (dist < MIN_DISTANCE || dist > MAX_DISTANCE) {
                int clamped = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, dist));
                double scale = rawDist > 1.0 ? clamped / rawDist : 1.0;
                tx = settlement.x() + (int) Math.round(dx * scale);
                tz = settlement.z() + (int) Math.round(dz * scale);
                dist = (int) Math.round(Math.hypot(tx - settlement.x(), tz - settlement.z()));
                dist = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, dist));
            }
            BlockPos targetPos = new BlockPos(tx, 72, tz);
            Size size = sizeOf(target, i);
            String jobId = "board_" + settlement.id() + "_" + epoch + "_" + i;

            out.add(buildJob(i, jobId, arch, settlement, target, targetPos, dist, size, jobSeed, beasts));
        }
        return out;
    }

    private static Job buildJob(int index, String jobId, String arch, Place settlement, Place target, BlockPos targetPos,
                                int dist, Size size, long jobSeed, List<BeastSpec> beasts) {
        return switch (arch) {
            case "bandit_bounty" -> {
                BossSpec boss = pick(BOSS_POOL, jobSeed, 1);
                String chiefName = Identity.bossName(target.id(), boss.name());
                Component title = Component.translatable("quest.skycraft.board.bandit_bounty.title", target.name());
                Component summary = Component.translatable("quest.skycraft.board.bandit_bounty.summary", chiefName, target.name(), dist, size.gold);
                Component desc = Component.translatable("quest.skycraft.board.bandit_bounty.desc", chiefName, target.name(), settlement.name(), size.gold);
                yield new Job(index, jobId, arch, Quest.Category.BOUNTY, settlement, target, targetPos, dist, size,
                        size.gold, size.xp, size.reputation, title, summary, desc,
                        boss.entity(), chiefName, "", "", 1, chiefName);
            }
            case "beast_hunt" -> {
                BeastSpec beast = pick(beasts, jobSeed, 2);
                String entityId = QuestSpawner.resolveType(beast.entity()).isPresent() ? beast.entity() : beast.fallback();
                Component title = Component.translatable("quest.skycraft.board.beast_hunt.title", beast.name(), target.name());
                Component summary = Component.translatable("quest.skycraft.board.beast_hunt.summary", beast.name(), target.name(), dist, size.gold);
                Component desc = Component.translatable("quest.skycraft.board.beast_hunt.desc", beast.name(), target.name(), settlement.name(), size.gold);
                yield new Job(index, jobId, arch, Quest.Category.BOUNTY, settlement, target, targetPos, dist, size,
                        size.gold, size.xp, size.reputation, title, summary, desc,
                        entityId, beast.name(), "", "", 1, beast.name());
            }
            case "lost_heirloom" -> {
                String item = pick(HEIRLOOM_ITEMS, jobSeed, 3);
                String resident = pick(HEIRLOOM_RESIDENTS, jobSeed, 4);
                Component title = Component.translatable("quest.skycraft.board.lost_heirloom.title", item, target.name());
                Component summary = Component.translatable("quest.skycraft.board.lost_heirloom.summary", item, target.name(), dist, size.gold);
                Component desc = Component.translatable("quest.skycraft.board.lost_heirloom.desc", item, resident, settlement.name(), target.name(), size.gold);
                yield new Job(index, jobId, arch, Quest.Category.SIDE, settlement, target, targetPos, dist, size,
                        size.gold, size.xp, size.reputation, title, summary, desc,
                        "", "", "skycraft:ancient_tome", item, 1, resident);
            }
            case "missing_person" -> {
                String person = pick(MISSING_NAMES, jobSeed, 5);
                Component title = Component.translatable("quest.skycraft.board.missing_person.title", person, target.name());
                Component summary = Component.translatable("quest.skycraft.board.missing_person.summary", person, target.name(), dist, size.gold);
                Component desc = Component.translatable("quest.skycraft.board.missing_person.desc", person, settlement.name(), target.name(), size.gold);
                yield new Job(index, jobId, arch, Quest.Category.SIDE, settlement, target, targetPos, dist, size,
                        size.gold, size.xp, size.reputation, title, summary, desc,
                        "", "", "", "", 1, person);
            }
            default -> {
                SupplySpec sup = pick(SUPPLIES, jobSeed, 6);
                Component title = Component.translatable("quest.skycraft.board.supply_order.title", sup.count(), sup.name(), settlement.name());
                Component summary = Component.translatable("quest.skycraft.board.supply_order.summary", sup.count(), sup.name(), target.name(), dist, size.gold);
                Component desc = Component.translatable("quest.skycraft.board.supply_order.desc", sup.count(), sup.name(), sup.recipient(), settlement.name(), target.name(), size.gold);
                yield new Job(index, jobId, "supply_order", Quest.Category.MISC, settlement, target, targetPos, dist, size,
                        size.gold, size.xp, size.reputation, title, summary, desc,
                        "", "", sup.item(), sup.name(), sup.count(), sup.recipient());
            }
        };
    }

    private static List<Place> candidateTargets(Place settlement, @Nullable ServerPlayer viewer) {
        PlayerData pdata = viewer != null ? SkyData.get(viewer) : null;
        List<Place> primary = new ArrayList<>();
        List<Place> clearedFallback = new ArrayList<>();

        for (Place p : Places.overlapping(settlement.x() - MAX_DISTANCE, settlement.z() - MAX_DISTANCE,
                settlement.x() + MAX_DISTANCE, settlement.z() + MAX_DISTANCE)) {
            if (p.id().equals(settlement.id()) || !TARGET_TYPES.contains(p.type())) continue;
            double d = Math.hypot(p.x() - settlement.x(), p.z() - settlement.z());
            if (d < MIN_DISTANCE || d > MAX_DISTANCE) continue;
            if (pdata != null && isCleared(pdata, p)) {
                clearedFallback.add(p);
            } else {
                primary.add(p);
            }
        }
        primary.sort(Comparator.comparing(Place::id));
        if (primary.size() >= MIN_JOBS) return primary;
        clearedFallback.sort(Comparator.comparing(Place::id));
        primary.addAll(clearedFallback);
        if (!primary.isEmpty()) return primary;

        // Sparse island fallback (e.g. Pyandonea): nearest dungeon places in the world
        List<Place> distant = new ArrayList<>();
        for (Place p : Places.all()) {
            if (! p.id().equals(settlement.id()) && TARGET_TYPES.contains(p.type())) distant.add(p);
        }
        distant.sort(Comparator.comparingLong(p -> p.distSqr(settlement.x(), settlement.z())));
        return distant.subList(0, Math.min(6, distant.size()));
    }

    private static boolean isCleared(PlayerData data, Place place) {
        CompoundTag loc = WorldData.find(data, WorldData.placeLocId(place.id()));
        return loc != null && loc.getBoolean("cleared");
    }

    private static String provinceOf(Place p) {
        if (p.regionId() != null && !p.regionId().isEmpty()) {
            int slash = p.regionId().indexOf('/');
            if (slash > 0) return p.regionId().substring(0, slash);
        }
        Region r = TamrielMap.region(p.x(), p.z());
        return r != null ? r.province() : "skyrim";
    }

    // ------------------------------------------------------------------ interaction & acceptance

    /** Lists the board jobs for {@code settlement} to {@code player} in chat with clickable [Accept] buttons. */
    public static int showBoard(ServerPlayer player, Place settlement) {
        List<Job> jobs = jobsFor(player.server, settlement, player);
        player.sendSystemMessage(Component.translatable("quest.skycraft.board.header", settlement.name())
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        if (jobs.isEmpty()) {
            player.sendSystemMessage(Component.translatable("quest.skycraft.board.empty").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        for (Job job : jobs) {
            boolean activeOrDone = Quests.find(player.server, player.getUUID(), job.id()) != null;
            MutableComponent line = Component.literal(" [" + job.index() + "] ").withStyle(ChatFormatting.YELLOW)
                    .append(job.title().copy().withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(job.distance() + "m").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" (" + job.size().name() + ", " + job.rewardGold() + "g)").withStyle(ChatFormatting.GOLD));
            if (activeOrDone) {
                line.append(Component.literal(" "))
                        .append(Component.translatable("quest.skycraft.board.taken").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                MutableComponent btn = Component.literal(" [")
                        .append(Component.translatable("quest.skycraft.board.accept_btn"))
                        .append(Component.literal("]"))
                        .withStyle(s -> s.withColor(ChatFormatting.GREEN)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                        "/skycraft-board accept " + settlement.id() + " " + job.index()))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, job.description())));
                line.append(btn);
            }
            player.sendSystemMessage(line);
        }
        return jobs.size();
    }

    /** Adds notice-board dialogue options when talking to an innkeeper or townsperson in a settlement. */
    public static void addDialogueOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!QuestDialogue.isInnkeeper(npc) && !QuestDialogue.isTownsperson(npc)) return;
        Optional<Place> placeOpt = nearestBoardSettlement(player.level(), npc.blockPosition(), 180);
        if (placeOpt.isEmpty()) return;
        Place settlement = placeOpt.get();
        out.add(new DialogueOption("quest.board.check." + settlement.id(),
                Component.translatable("dialogue.skycraft.quest.notice_board"), 48,
                (pl, n) -> openBoardDialogue(pl, n, settlement)));
        List<Job> jobs = jobsFor(player.server, settlement, player);
        for (Job job : jobs) {
            if (Quests.find(player.server, player.getUUID(), job.id()) != null) continue;
            out.add(new DialogueOption("quest.board.take." + job.index(),
                    Component.translatable("dialogue.skycraft.quest.board_entry", job.title(), job.distance(), job.rewardGold()),
                    50 + job.index(),
                    (pl, n) -> {
                        if (accept(pl, settlement, job.index())) {
                            Dialogue.open(pl, n, Component.translatable("quest.skycraft.board.accepted_reply", job.targetPlace().name(), job.rewardGold()));
                        }
                    }));
        }
    }

    public static void openBoardDialogue(ServerPlayer player, LivingEntity speaker, Place settlement) {
        showBoard(player, settlement);
        Dialogue.open(player, speaker, Component.translatable("quest.skycraft.board.innkeeper_intro", settlement.name()));
    }

    /** Accepts the job at {@code index} on {@code settlement}'s notice board for {@code player} (and their party). */
    public static boolean accept(ServerPlayer player, Place settlement, int index) {
        if (!Quests.canTakeMore(player)) {
            Notifier.message(player, Component.translatable("quest.skycraft.too_many"));
            return false;
        }
        List<Job> jobs = jobsFor(player.server, settlement, player);
        if (jobs.isEmpty()) return false;
        int resolved = index;
        if (resolved < 0 || resolved >= jobs.size()) {
            if (index >= 1 && index <= jobs.size()) resolved = index - 1;
            else {
                player.sendSystemMessage(Component.translatable("quest.skycraft.board.invalid_index", jobs.size() - 1).withStyle(ChatFormatting.RED));
                return false;
            }
        }
        Job job = jobs.get(resolved);
        if (Quests.find(player.server, player.getUUID(), job.id()) != null) {
            player.sendSystemMessage(Component.translatable("quest.skycraft.board.already_taken").withStyle(ChatFormatting.RED));
            return false;
        }

        Quest q = toQuest(player.level(), job);
        if (!Quests.start(player, q)) return false;

        // Track the newly accepted board quest and mark the target Place on every party member's map and compass
        String ownerKey = Quests.keyForNew(player.server, player.getUUID());
        for (ServerPlayer member : Quests.onlineMembers(player.server, ownerKey)) {
            markPlaceKnown(member, job.targetPlace());
            PlayerData md = SkyData.get(member);
            md.module("quest").putString("tracked", q.id);
            md.markDirty();
            Quests.markPlayerDirty(member.getUUID());
        }
        return true;
    }

    /** Builds the playable {@link Quest} for a notice board {@link Job}. */
    public static Quest toQuest(Level level, Job job) {
        Quest q = new Quest("board_" + job.archetype(), job.category(), job.title(), job.description());
        q.id = job.id();
        q.giverName = Component.translatable("quest.skycraft.board.giver", job.settlement().name());
        q.giverPos = new BlockPos(job.settlement().x(), 72, job.settlement().z());
        q.giverDim = "minecraft:overworld";
        q.hold = !job.settlement().regionId().isEmpty()
                ? Holds.migrate(job.settlement().regionId())
                : Holds.holdAt(level, q.giverPos);
        q.rewardGold = job.rewardGold();
        q.extra.putString("board_settlement", job.settlement().id());
        q.extra.putString("target_place", job.targetPlace().id());
        q.extra.putString("board_size", job.size().name());
        q.extra.putInt("board_xp", job.rewardXp());
        q.extra.putInt("board_rep", job.rewardRep());

        String targetLabel = job.targetPlace().name().getString();
        String settlementLabel = job.settlement().name().getString();
        int settleRadius = Math.max(48, job.settlement().radius());

        switch (job.archetype()) {
            case "bandit_bounty" -> {
                CompoundTag chief = QuestSpawner.Spawn.of(job.targetEntity(), 1)
                        .name(Component.literal(job.personName()))
                        .health(3.0f).damage(1.6f).target().boss().build();
                CompoundTag guards = QuestSpawner.Spawn.of("minecraft:pillager", 2)
                        .health(1.4f).damage(1.2f).build();
                q.add(new Objective(Objective.Type.KILL_TARGET,
                        Component.translatable("quest.skycraft.board.bandit_bounty.obj", job.personName(), job.targetPlace().name()))
                        .at(job.targetPos(), "minecraft:overworld", 24).guessY()
                        .placement("structure").spawn(chief).spawn(guards).label(targetLabel));
            }
            case "beast_hunt" -> {
                CompoundTag beast = QuestSpawner.Spawn.of(job.targetEntity(), 1)
                        .name(Component.literal(job.personName()))
                        .health(3.4f).damage(1.7f).target().boss().build();
                q.add(new Objective(Objective.Type.KILL_TARGET,
                        Component.translatable("quest.skycraft.board.beast_hunt.obj", job.personName(), job.targetPlace().name()))
                        .at(job.targetPos(), "minecraft:overworld", 24).guessY()
                        .placement("surface").spawn(beast)
                        .onComplete("trophy:" + job.personName() + ":" + (job.rewardGold() / 2))
                        .label(targetLabel));
                q.add(new Objective(Objective.Type.TALK_TO,
                        Component.translatable("quest.skycraft.board.beast_hunt.return", job.settlement().name()))
                        .at(q.giverPos, "minecraft:overworld", settleRadius).guessY()
                        .filter("any")
                        .topic(Component.translatable("dialogue.skycraft.quest.board_turnin", job.personName()),
                                Component.translatable("quest.skycraft.board.turnin_reply", job.rewardGold()))
                        .label(settlementLabel));
            }
            case "lost_heirloom" -> {
                CompoundTag guards = QuestSpawner.Spawn.of("minecraft:skeleton", 2)
                        .health(1.5f).damage(1.2f).build();
                q.add(new Objective(Objective.Type.GO_TO,
                        Component.translatable("quest.skycraft.board.lost_heirloom.obj_go", job.targetItemName(), job.targetPlace().name()))
                        .at(job.targetPos(), "minecraft:overworld", 14).guessY()
                        .placement("structure").spawn(guards)
                        .onComplete("give:skycraft:ancient_tome")
                        .label(targetLabel));
                q.add(new Objective(Objective.Type.FETCH,
                        Component.translatable("quest.skycraft.board.lost_heirloom.obj_return", job.targetItemName(), job.personName(), job.settlement().name()))
                        .target("skycraft:ancient_tome").count(1)
                        .at(q.giverPos, "minecraft:overworld", settleRadius).guessY()
                        .filter("any")
                        .topic(Component.translatable("dialogue.skycraft.quest.board_heirloom", job.targetItemName()),
                                Component.translatable("quest.skycraft.board.turnin_reply", job.rewardGold()))
                        .label(settlementLabel));
            }
            case "missing_person" -> {
                CompoundTag captors = QuestSpawner.Spawn.of("minecraft:pillager", 2)
                        .health(1.4f).damage(1.2f).build();
                q.add(new Objective(Objective.Type.GO_TO,
                        Component.translatable("quest.skycraft.board.missing_person.obj_search", job.personName(), job.targetPlace().name()))
                        .at(job.targetPos(), "minecraft:overworld", 16).guessY()
                        .placement("surface").spawn(captors)
                        .label(targetLabel));
                q.add(new Objective(Objective.Type.TALK_TO,
                        Component.translatable("quest.skycraft.board.missing_person.obj_report", job.personName(), job.settlement().name()))
                        .at(q.giverPos, "minecraft:overworld", settleRadius).guessY()
                        .filter("any")
                        .topic(Component.translatable("dialogue.skycraft.quest.board_missing", job.personName()),
                                Component.translatable("quest.skycraft.board.turnin_reply", job.rewardGold()))
                        .label(settlementLabel));
            }
            default -> {
                q.add(new Objective(Objective.Type.GO_TO,
                        Component.translatable("quest.skycraft.board.supply_order.obj_gather", job.targetCount(), job.targetItemName(), job.targetPlace().name()))
                        .at(job.targetPos(), "minecraft:overworld", 18).guessY()
                        .placement("surface")
                        .onComplete("give:" + job.targetItem() + ":" + job.targetCount())
                        .label(targetLabel));
                q.add(new Objective(Objective.Type.FETCH,
                        Component.translatable("quest.skycraft.board.supply_order.obj_deliver", job.targetCount(), job.targetItemName(), job.personName(), job.settlement().name()))
                        .target(job.targetItem()).count(job.targetCount())
                        .at(q.giverPos, "minecraft:overworld", settleRadius).guessY()
                        .filter("any")
                        .topic(Component.translatable("dialogue.skycraft.quest.board_supply", job.targetCount(), job.targetItemName()),
                                Component.translatable("quest.skycraft.board.turnin_reply", job.rewardGold()))
                        .label(settlementLabel));
            }
        }
        return q;
    }

    /** Marks {@code place} as heard-of ("known") on {@code player}'s world map so it appears on the map and compass. */
    public static void markPlaceKnown(ServerPlayer player, Place place) {
        if (place == null) return;
        PlayerData data = SkyData.get(player);
        String locId = WorldData.placeLocId(place.id());
        if (WorldData.find(data, locId) != null) return;
        LocationKind kind = LocationKind.forPlace(place);
        CompoundTag loc = new CompoundTag();
        loc.putString("id", locId);
        loc.putString("place", place.id());
        loc.putString("name", place.name().getString());
        loc.putString("type", kind.id);
        loc.putInt("x", place.x());
        loc.putInt("y", 72);
        loc.putInt("z", place.z());
        loc.putString("dim", "minecraft:overworld");
        loc.putBoolean("known", true);
        WorldData.discovered(data).add(loc);
        data.markDirty();
    }

    /** Awards bonus XP and hold reputation when a notice board job is completed by a player or party. */
    public static void onQuestCompleted(MinecraftServer server, String ownerKey, Quest q) {
        if (q == null || !q.kind.startsWith("board_")) return;
        int xp = q.extra.getInt("board_xp");
        int rep = q.extra.getInt("board_rep");
        for (ServerPlayer member : Quests.onlineMembers(server, ownerKey)) {
            PlayerData data = SkyData.get(member);
            if (xp > 0) {
                com.skycraft.skills.Progression.awardQuestXp(member, xp);
            }
            if (rep > 0 && !q.hold.isEmpty()) {
                CompoundTag holdRep = data.module("quest").getCompound("hold_reputation");
                holdRep.putInt(q.hold, holdRep.getInt(q.hold) + rep);
                data.module("quest").put("hold_reputation", holdRep);
                data.markDirty();
            }
        }
    }

    // ------------------------------------------------------------------ selftest

    /**
     * Asserts that every town, city and capital in {@link Places#all()} generates {@code >= 3} valid jobs targeting
     * real places within {@code 300..1500} blocks.
     */
    public static List<String> selftest(@Nullable MinecraftServer server) {
        List<String> problems = new ArrayList<>();
        int settlements = 0;
        int totalJobs = 0;
        for (Place p : Places.all()) {
            if (p.type() != PlaceType.TOWN && p.type() != PlaceType.CITY && p.type() != PlaceType.CAPITAL) continue;
            settlements++;
            List<Job> jobs = jobsFor(server, p, null);
            if (jobs.size() < MIN_JOBS || jobs.size() > MAX_JOBS) {
                problems.add(p.id() + ": generated " + jobs.size() + " jobs (expected 3..6)");
                continue;
            }
            for (Job j : jobs) {
                totalJobs++;
                if (j.targetPlace() == null || Places.byId(j.targetPlace().id()).isEmpty()) {
                    problems.add(p.id() + " job[" + j.index() + "]: target place is missing");
                }
                if (j.distance() < MIN_DISTANCE || j.distance() > MAX_DISTANCE) {
                    problems.add(p.id() + " job[" + j.index() + "]: distance " + j.distance() + " outside 300..1500");
                }
                if (j.rewardGold() != j.size().gold) {
                    problems.add(p.id() + " job[" + j.index() + "]: reward " + j.rewardGold() + " != " + j.size().gold);
                }
            }
        }
        if (settlements == 0) problems.add("No towns/cities/capitals found in Places.all()");
        Skycraft.LOGGER.info("NoticeBoards selftest checked {} settlements ({} jobs), {} problems",
                settlements, totalJobs, problems.size());
        return problems;
    }

    // ------------------------------------------------------------------ deterministic math

    private static long mix(long a, long b, long c) {
        long h = a ^ (b * 0x9E3779B97F4A7C15L) ^ (c * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    private static <T> T pick(List<T> list, long seed, int salt) {
        return list.get((int) Math.floorMod(mix(seed, salt, 0x517CC1B727220A95L), list.size()));
    }
}
