package com.skycraft.creatures.spawn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.skycraft.Skycraft;
import com.skycraft.core.Holds;
import com.skycraft.creatures.Leveling;
import com.skycraft.network.NotifyKind;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.Objective;
import com.skycraft.quest.Quest;
import com.skycraft.quest.Quests;
import com.skycraft.quest.SideQuests;
import com.skycraft.core.Notifier;
import com.skycraft.quest.story.Story;
import com.skycraft.quest.story.StoryData;
import com.skycraft.roads.RoadCells;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.HayBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data-driven wilderness & road encounters ({@code /skycraft/creatures/scenes.json}):
 * multi-actor scenes, wonder moments ({@code "wonder": true}), world-sheet citations ({@code "why"}),
 * and optional one-shot quest hooks ({@code "questHook"}).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class WildScenes {
    public static final String RESOURCE = "/skycraft/creatures/scenes.json";
    public static final String TAG_SCENE = "skycraft_scene";
    public static final String TAG_SCENE_ID = "skycraft_scene_id";
    public static final String TAG_QUEST_HOOK = "skycraft_scene_quest_hook";
    public static final String TAG_HOOK_CLAIMED = "skycraft_scene_hook_claimed";

    public static final List<String> SKYRIM_HOLDS = List.of(
            "whiterun", "haafingar", "eastmarch", "the_rift", "the_reach",
            "falkreath", "hjaalmarch", "the_pale", "winterhold"
    );

    public record Actor(
            @Nullable NpcRole npc,
            @Nullable EntityType<?> entity,
            int min,
            int max,
            int radius,
            boolean hostile,
            String group,
            String pose,
            int leash
    ) {}

    public record Scene(
            String id,
            int weight,
            boolean wonder,
            String road,
            String time,
            double minWildness,
            Set<String> provinces,
            Set<String> regions,
            Set<String> cultures,
            Set<BiomeFamily> families,
            String purpose,
            List<String> needs,
            List<String> why,
            String questHook,
            List<String> props,
            List<Actor> actors,
            Map<String, Integer> minFactionRep,
            Map<String, Integer> maxFactionRep,
            String requiredDeed,
            Set<String> playerRaces,
            Set<String> rulingFactions
    ) {
        public Scene(
                String id,
                int weight,
                boolean wonder,
                String road,
                String time,
                double minWildness,
                Set<String> provinces,
                Set<String> regions,
                Set<String> cultures,
                Set<BiomeFamily> families,
                String purpose,
                List<String> needs,
                List<String> why,
                String questHook,
                List<String> props,
                List<Actor> actors
        ) {
            this(id, weight, wonder, road, time, minWildness, provinces, regions, cultures, families,
                    purpose, needs, why, questHook, props, actors,
                    Map.of(), Map.of(), "", Set.of(), Set.of());
        }

        public boolean matchesHold(String holdShortOrFull) {
            String shortId = holdShortOrFull.contains("/")
                    ? holdShortOrFull.substring(holdShortOrFull.indexOf('/') + 1)
                    : holdShortOrFull;
            String fullId = "skyrim/" + shortId;
            return regions.contains(shortId) || regions.contains(fullId);
        }

        public boolean isPoliticalOrStateFiltered() {
            return !minFactionRep.isEmpty() || !maxFactionRep.isEmpty() || !requiredDeed.isEmpty()
                    || !playerRaces.isEmpty() || !rulingFactions.isEmpty();
        }
    }

    private static volatile List<Scene> scenes;
    private static final Map<UUID, Long> COOLDOWN = new ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_TICKS = 20L * 75L;

    private WildScenes() {}

    public static List<Scene> all() {
        List<Scene> s = scenes;
        if (s != null) return s;
        synchronized (WildScenes.class) {
            if (scenes == null) scenes = load();
            return scenes;
        }
    }

    public static Optional<Scene> byId(String id) {
        if (id == null || id.isEmpty()) return Optional.empty();
        for (Scene s : all()) {
            if (s.id.equalsIgnoreCase(id)) return Optional.of(s);
        }
        return Optional.empty();
    }

    public static List<Scene> forRegion(Region region) {
        if (region == null) return List.of();
        List<Scene> out = new ArrayList<>();
        for (Scene s : all()) {
            if (matchesRegion(s, region)) out.add(s);
        }
        return out;
    }

    public static List<Scene> forHold(String holdId) {
        String shortId = holdId.contains("/") ? holdId.substring(holdId.indexOf('/') + 1) : holdId;
        String fullId = "skyrim/" + shortId;
        List<Scene> out = new ArrayList<>();
        for (Scene s : all()) {
            if (s.regions.contains(shortId) || s.regions.contains(fullId)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Evaluates whether a scene's political, faction-reputation, deed, and player-race conditions match.
     */
    public static boolean matchesState(
            Scene s,
            @Nullable String regionOrPlaceId,
            @Nullable String playerRace,
            @Nullable Set<String> activeDeeds,
            @Nullable java.util.function.ToIntFunction<String> repLookup
    ) {
        if (s == null) return false;
        if (!s.rulingFactions.isEmpty()) {
            String ruling = regionOrPlaceId != null ? com.skycraft.society.Reputation.rulingFaction(regionOrPlaceId) : null;
            if (ruling == null || !s.rulingFactions.contains(ruling)) return false;
        }
        if (!s.playerRaces.isEmpty()) {
            if (playerRace == null || playerRace.isEmpty()) return false;
            if (!s.playerRaces.contains(playerRace.toLowerCase(Locale.ROOT))) return false;
        }
        if (!s.requiredDeed.isEmpty()) {
            if (activeDeeds == null || !activeDeeds.contains(s.requiredDeed)) return false;
        }
        if (!s.minFactionRep.isEmpty()) {
            for (Map.Entry<String, Integer> e : s.minFactionRep.entrySet()) {
                int rep = repLookup != null ? repLookup.applyAsInt(e.getKey()) : 0;
                if (rep < e.getValue()) return false;
            }
        }
        if (!s.maxFactionRep.isEmpty()) {
            for (Map.Entry<String, Integer> e : s.maxFactionRep.entrySet()) {
                int rep = repLookup != null ? repLookup.applyAsInt(e.getKey()) : 0;
                if (rep > e.getValue()) return false;
            }
        }
        return true;
    }

    /**
     * Evaluates whether a scene's region and player/world-state conditions match {@code player} in {@code region}.
     */
    public static boolean matchesPlayerAndState(
            Scene s,
            @Nullable net.minecraft.world.entity.player.Player player,
            @Nullable Region region
    ) {
        if (s == null) return false;
        if (region != null && !matchesRegion(s, region)) return false;
        String regionId = region != null ? region.id() : null;
        if (player == null) {
            return matchesState(s, regionId, null, Set.of(), f -> 0);
        }
        com.skycraft.core.Race r = com.skycraft.core.SkyData.get(player).getRace();
        String pRace = r != null ? r.id() : "nord";
        Set<String> deeds = (player instanceof ServerPlayer sp)
                ? new HashSet<>(com.skycraft.society.ContextualDialogue.activeDeeds(sp, region != null ? region.shortId() : null))
                : Set.of();
        return matchesState(s, regionId, pRace, deeds, f -> com.skycraft.society.Reputation.get(player, f));
    }

    /**
     * Returns all scenes eligible for {@code player} in {@code region} based on region and player/world-state filters.
     */
    public static List<Scene> eligibleFor(@Nullable net.minecraft.world.entity.player.Player player, Region region) {
        if (region == null) return List.of();
        List<Scene> out = new ArrayList<>();
        for (Scene s : all()) {
            if (matchesPlayerAndState(s, player, region)) out.add(s);
        }
        return out;
    }

    /**
     * Called by {@link RegionSpawner} when a spawn cycle has a free roll: has a small chance to place a multi-actor
     * scene instead of a plain pack. Returns the number of mobs spawned (0 when no scene rolled).
     */
    public static int tryRoll(ServerLevel level, ServerPlayer player, BlockPos pos, Region region, BiomeFamily family,
                              double wildness, RandomSource random) {
        if (random.nextInt(5) != 0) return 0;
        long now = level.getGameTime();
        Long prev = COOLDOWN.get(player.getUUID());
        if (prev != null && now - prev < MIN_INTERVAL_TICKS) return 0;

        if (!level.getEntitiesOfClass(Mob.class, new AABB(pos).inflate(64),
                m -> m.getPersistentData().getBooleanOr(TAG_SCENE, false)).isEmpty()) return 0;
        if (SpawnPacing.settlementWithin(level, pos, 80)) return 0;

        List<Scene> matching = new ArrayList<>();
        int total = 0;
        for (Scene s : all()) {
            if (!matches(s, level, pos, region, family, wildness, player)) continue;
            matching.add(s);
            total += effectiveWeight(s);
        }
        if (total <= 0) return 0;
        int r = random.nextInt(total);
        Scene chosen = matching.get(0);
        for (Scene s : matching) {
            r -= effectiveWeight(s);
            if (r < 0) {
                chosen = s;
                break;
            }
        }
        int made = spawn(level, pos, chosen, random, player);
        if (made > 0) COOLDOWN.put(player.getUUID(), now);
        return made;
    }

    private static int effectiveWeight(Scene s) {
        return s.isPoliticalOrStateFiltered() ? Math.max(1, s.weight * 2) : Math.max(1, s.weight);
    }

    /**
     * Rolls or forces a scene near {@code player} (used by {@link com.skycraft.society.Encounters} and
     * {@code /skycraft-scenes roll}). Returns the spawned {@link Scene}, or {@code null}.
     */
    @Nullable
    public static Scene rollNearPlayer(ServerPlayer player, @Nullable String forcedId) {
        ServerLevel level = player.level();
        RandomSource random = player.getRandom();
        BlockPos center = findSurfaceNear(level, player.blockPosition(), 24, 56, random);
        if (center == null) center = player.blockPosition();
        Region region = Regions.at(level, center);
        BiomeFamily family = BiomeFamily.of(level.getBiome(center), center, level.getSeaLevel());
        double wild = SpawnPacing.settlementWithin(level, center, 160) ? 0.1
                : (SpawnPacing.onRoad(level, center) ? 0.35 : 0.85);

        Scene chosen = null;
        if (forcedId != null && !forcedId.isEmpty()) {
            chosen = byId(forcedId).orElse(null);
            if (chosen == null) return null;
        } else {
            List<Scene> matching = new ArrayList<>();
            int total = 0;
            for (Scene s : all()) {
                if (!matches(s, level, center, region, family, wild, player)) continue;
                matching.add(s);
                total += effectiveWeight(s);
            }
            if (total <= 0) return null;
            int r = random.nextInt(total);
            chosen = matching.get(0);
            for (Scene s : matching) {
                r -= effectiveWeight(s);
                if (r < 0) {
                    chosen = s;
                    break;
                }
            }
        }
        int made = spawn(level, center, chosen, random, player);
        if (made <= 0) return null;
        COOLDOWN.put(player.getUUID(), level.getGameTime());
        return chosen;
    }

    @Nullable
    private static BlockPos findSurfaceNear(ServerLevel level, BlockPos origin, int minR, int maxR, RandomSource r) {
        for (int i = 0; i < 16; i++) {
            double ang = r.nextDouble() * Math.PI * 2.0;
            double dist = minR + r.nextDouble() * (maxR - minR);
            int x = Mth.floor(origin.getX() + Math.cos(ang) * dist);
            int z = Mth.floor(origin.getZ() + Math.sin(ang) * dist);
            BlockPos ground = surface(level, x, z, origin.getY());
            if (ground != null) return ground;
        }
        return surface(level, origin.getX(), origin.getZ(), origin.getY());
    }

    private static boolean matchesRegion(Scene s, Region region) {
        if (!s.regions.isEmpty()) {
            return s.regions.contains(region.shortId()) || s.regions.contains(region.id());
        }
        if (!s.provinces.isEmpty()) {
            return s.provinces.contains(region.province());
        }
        if (!s.cultures.isEmpty()) {
            return s.cultures.contains(region.culture());
        }
        return true;
    }

    private static boolean matches(Scene s, ServerLevel level, BlockPos pos, Region region, BiomeFamily family,
                                   double wild, @Nullable ServerPlayer player) {
        if (wild < s.minWildness) return false;
        if (!matchesRegion(s, region)) return false;
        if (!matchesPlayerAndState(s, player, region)) return false;
        if (!s.families.isEmpty() && !s.families.contains(family)) return false;
        if ("day".equals(s.time) && !level.isBrightOutside()) return false;
        if ("night".equals(s.time) && !level.isDarkOutside()) return false;
        if ("dawn_dusk".equals(s.time)) {
            long tod = level.getOverworldClockTime() % 24000L;
            boolean dawnOrDusk = tod <= 3000L || (tod >= 11000L && tod <= 14500L) || tod >= 22000L;
            if (!dawnOrDusk && !level.isBrightOutside()) return false;
        }
        if (!s.road.isEmpty()) {
            boolean on = RoadCells.isRoad(level, pos.getX(), pos.getZ(), 10);
            boolean near = on || RoadCells.isRoad(level, pos.getX(), pos.getZ(), 32);
            if ("on".equals(s.road) && !on) return false;
            if ("near".equals(s.road) && !near) return false;
            if ("off".equals(s.road) && near) return false;
        }
        boolean peaceful = level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL;
        if (peaceful) {
            for (Actor a : s.actors) if (a.hostile) return false;
        }
        return true;
    }

    public static int spawn(ServerLevel level, BlockPos origin, Scene s, RandomSource r, @Nullable ServerPlayer nearPlayer) {
        BlockPos center = surface(level, origin.getX(), origin.getZ(), origin.getY());
        if (center == null) return 0;
        placeProps(level, center, s.props, r);

        boolean hasVictims = false;
        for (Actor a : s.actors) {
            if ("victims".equals(a.group)) {
                hasVictims = true;
                break;
            }
        }

        Map<String, List<Mob>> groups = new HashMap<>();
        List<Mob> spawnedAll = new ArrayList<>();
        int count = 0;
        for (Actor a : s.actors) {
            int n = a.min + (a.max > a.min ? r.nextInt(a.max - a.min + 1) : 0);
            for (int i = 0; i < n; i++) {
                int dx = a.radius <= 0 ? 0 : r.nextInt(a.radius * 2 + 1) - a.radius;
                int dz = a.radius <= 0 ? 0 : r.nextInt(a.radius * 2 + 1) - a.radius;
                BlockPos at = surface(level, center.getX() + dx, center.getZ() + dz, center.getY());
                if (at == null) continue;
                Mob m = spawnActor(level, at, a, s, r, nearPlayer, hasVictims);
                if (m == null) continue;
                count++;
                spawnedAll.add(m);
                if (!a.group.isEmpty()) groups.computeIfAbsent(a.group, k -> new ArrayList<>()).add(m);
            }
        }
        wireScene(groups);

        if (count > 0 && nearPlayer != null) {
            if (s.wonder) {
                com.skycraft.core.Notifier.message(nearPlayer, Component.translatable("scene.skycraft.wonder." + s.id));
                level.playSound(null, nearPlayer.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
                        SoundSource.AMBIENT, 0.8f, 1.0f);
            }
            // If all actors are hostile or non-NPC creatures and the scene carries a quest hook, offer on spawn
            boolean hasFriendlyNpc = false;
            for (Actor a : s.actors) {
                if (a.npc != null && !a.hostile) {
                    hasFriendlyNpc = true;
                    break;
                }
            }
            if (!s.questHook.isEmpty() && !hasFriendlyNpc && !spawnedAll.isEmpty()) {
                triggerQuestHook(nearPlayer, spawnedAll.get(0), s.questHook, s.id);
            }
        }
        return count;
    }

    @Nullable
    private static Mob spawnActor(ServerLevel level, BlockPos pos, Actor a, Scene scene, RandomSource r,
                                  @Nullable ServerPlayer nearPlayer, boolean hasVictims) {
        Mob mob = null;
        if (a.npc != null) {
            var npc = Npcs.create(level, pos, a.npc);
            if (npc != null && level.addFreshEntity(npc)) {
                if (a.hostile && nearPlayer != null && !hasVictims && ("attackers".equals(a.group) || a.group.isEmpty())) {
                    npc.setHuntTarget(nearPlayer.getUUID());
                }
                mob = npc;
            }
        } else if (a.entity != null) {
            Entity e = a.entity.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (!(e instanceof Mob m)) return null;
            m.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, r.nextFloat() * 360f, 0f);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.NATURAL, null);
            Leveling.apply(m, Leveling.regionLevel(level, pos));
            if (!level.addFreshEntity(m)) return null;
            mob = m;
        }
        if (mob == null) return null;
        mob.getPersistentData().putBoolean(TAG_SCENE, true);
        mob.getPersistentData().putString(TAG_SCENE_ID, scene.id);
        if (!scene.questHook.isEmpty()) {
            mob.getPersistentData().putString(TAG_QUEST_HOOK, scene.questHook);
        }
        if ("crouch".equals(a.pose)) mob.setPose(Pose.CROUCHING);
        if (a.leash > 0 && mob instanceof PathfinderMob pm) pm.setHomeTo(pos, a.leash);
        return mob;
    }

    private static void wireScene(Map<String, List<Mob>> groups) {
        pairFight(groups.get("attackers"), groups.get("victims"));
        pairFight(groups.get("hunters"), groups.get("prey"));
        pairFight(groups.get("companions"), groups.get("beasts"));
        pairFight(groups.get("orcs"), groups.get("beast"));
        pairFight(groups.get("imperials"), groups.get("stormcloaks"));
        pairFight(groups.get("stormcloaks"), groups.get("imperials"));
        pairFight(groups.get("crowns"), groups.get("forebears"));
        pairFight(groups.get("forebears"), groups.get("crowns"));
        pairFight(groups.get("redoran"), groups.get("ashlanders"));
        pairFight(groups.get("ashlanders"), groups.get("redoran"));
        pairFight(groups.get("telvanni"), groups.get("mages"));
        pairFight(groups.get("mages"), groups.get("telvanni"));
        pairFight(groups.get("an_xileel"), groups.get("slavers"));
        pairFight(groups.get("slavers"), groups.get("an_xileel"));
        pairFight(groups.get("thalmor"), groups.get("dissidents"));
        pairFight(groups.get("dissidents"), groups.get("thalmor"));
        pairFight(groups.get("vigilants"), groups.get("daedra"));
        pairFight(groups.get("daedra"), groups.get("vigilants"));
    }

    private static void pairFight(@Nullable List<Mob> a, @Nullable List<Mob> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return;
        for (int i = 0; i < a.size(); i++) {
            Mob attacker = a.get(i);
            Mob target = b.get(i % b.size());
            attacker.setTarget(target);
        }
    }

    // ---------------------------------------------------------------- quest hook interaction

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof LivingEntity target)) return;
        if (!target.getPersistentData().contains(TAG_QUEST_HOOK)) return;
        if (target.getPersistentData().getBooleanOr(TAG_HOOK_CLAIMED, false)) return;
        String hook = target.getPersistentData().getStringOr(TAG_QUEST_HOOK, "");
        String sceneId = target.getPersistentData().getStringOr(TAG_SCENE_ID, "");
        if (hook.isEmpty()) return;
        if (triggerQuestHook(player, target, hook, sceneId)) {
            target.getPersistentData().putBoolean(TAG_HOOK_CLAIMED, true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    public static boolean triggerQuestHook(ServerPlayer player, LivingEntity giver, String hook, String sceneId) {
        if (hook == null || hook.isEmpty() || !Quests.canTakeMore(player)) return false;
        // 1. Dedicated SideQuests
        switch (hook) {
            case "side_golden_claw" -> {
                return SideQuests.startGoldenClaw(player, giver);
            }
            case "side_time_of_need" -> {
                return SideQuests.startTimeOfNeed(player, giver);
            }
            case "side_waking_nightmare" -> {
                return SideQuests.startWakingNightmare(player, giver);
            }
            case "side_forsworn_conspiracy" -> {
                return SideQuests.startForswornConspiracy(player, giver);
            }
            default -> {}
        }
        // 2. Story quest definitions in StoryData
        StoryData.QuestDef def = StoryData.quest(hook);
        if (def != null) {
            return Story.start(player, def);
        }
        // 3. One-shot radiant encounter quest hooks (radiant_bounty, radiant_hunt, radiant_favor)
        ServerLevel level = player.level();
        String dim = level.dimension().identifier().toString();
        String hold = Holds.holdAt(level, giver.blockPosition());
        String qId = "scene_" + (sceneId == null || sceneId.isEmpty() ? hook : sceneId);
        if (Quests.find(player.level().getServer(), player.getUUID(), qId) != null) return false;

        Quest q = new Quest(qId, Quest.Category.MISC,
                Component.translatable("scene.skycraft.quest." + hook + ".title"),
                Component.translatable("scene.skycraft.quest." + hook + ".desc"));
        q.giver = giver.getUUID();
        q.giverName = giver.getDisplayName();
        q.giverPos = giver.blockPosition();
        q.giverDim = dim;
        q.hold = hold;

        if ("radiant_hunt".equals(hook)) {
            Objective obj = new Objective(Objective.Type.KILL_TYPE_COUNT,
                    Component.translatable("scene.skycraft.quest.radiant_hunt.obj"))
                    .target("skycraft:elk,skycraft:deer,skycraft:bear,skycraft:sabre_cat,skycraft:wolf")
                    .count(2);
            q.add(obj).reward(120);
        } else if ("radiant_bounty".equals(hook)) {
            Objective obj = new Objective(Objective.Type.KILL_TYPE_COUNT,
                    Component.translatable("scene.skycraft.quest.radiant_bounty.obj"))
                    .target("skycraft:bandit,skycraft:draugr,skycraft:forsworn,skycraft:vampire,skycraft:necromancer")
                    .count(3);
            q.add(obj).reward(180);
        } else {
            Objective obj = new Objective(Objective.Type.GO_TO,
                    Component.translatable("scene.skycraft.quest.radiant_favor.obj"))
                    .at(giver.blockPosition().offset(48, 0, 48), dim, 24)
                    .guessY()
                    .label("Roadside Landmark");
            q.add(obj).reward(100);
        }
        boolean started = Quests.start(player, q);
        if (started) {
            Notifier.send(player, NotifyKind.QUEST_STARTED, q.title, q.description);
        }
        return started;
    }

    // ---------------------------------------------------------------- props

    private static void placeProps(ServerLevel level, BlockPos center, List<String> props, RandomSource r) {
        if (props.isEmpty()) return;
        for (String p : props) {
            switch (p) {
                case "fire" -> setProp(level, center, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
                case "log" -> {
                    Direction.Axis axis = r.nextBoolean() ? Direction.Axis.X : Direction.Axis.Z;
                    BlockPos pos = surface(level, center.getX() + (axis == Direction.Axis.X ? 0 : 2),
                            center.getZ() + (axis == Direction.Axis.Z ? 0 : 2), center.getY());
                    if (pos != null) setProp(level, pos, Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis));
                }
                case "meat" -> {
                    BlockPos pos = surface(level, center.getX() - 1, center.getZ() + 1, center.getY());
                    if (pos != null) setProp(level, pos, Blocks.COARSE_DIRT.defaultBlockState());
                }
                case "cart" -> {
                    BlockPos pos = surface(level, center.getX() + 2, center.getZ() - 1, center.getY());
                    if (pos != null) {
                        setProp(level, pos, Blocks.SPRUCE_SLAB.defaultBlockState());
                        setProp(level, pos.above(), Blocks.BARREL.defaultBlockState());
                    }
                }
                case "sack" -> {
                    BlockPos pos = surface(level, center.getX() + 1, center.getZ() + 1, center.getY());
                    if (pos != null) setProp(level, pos, Blocks.HAY_BLOCK.defaultBlockState().setValue(HayBlock.AXIS, Direction.Axis.Y));
                }
                case "lantern" -> {
                    BlockPos pos = surface(level, center.getX() - 1, center.getZ() - 1, center.getY());
                    if (pos != null) setProp(level, pos, Blocks.LANTERN.defaultBlockState());
                }
                default -> {}
            }
        }
    }

    private static void setProp(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState cur = level.getBlockState(pos);
        if (!cur.isAir() && !cur.canBeReplaced()) return;
        if (!level.getFluidState(pos).isEmpty()) return;
        if (!level.getBlockState(pos.below()).isSolidRender()) return;
        level.setBlock(pos, state, 2);
    }

    @Nullable
    private static BlockPos surface(ServerLevel level, int x, int z, int nearY) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinY() + 1 || Math.abs(y - nearY) > 16) return null;
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getFluidState(pos).isEmpty() || level.getFluidState(pos.below()).is(FluidTags.WATER)) return null;
        return pos;
    }

    // ---------------------------------------------------------------- commands (/skycraft-scenes)

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommand(event.getDispatcher());
    }

    private static void registerCommand(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("skycraft-scenes").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("list")
                        .executes(c -> {
                            List<Scene> list = all();
                            long wonders = list.stream().filter(Scene::wonder).count();
                            c.getSource().sendSuccess(() -> Component.literal(
                                    "Encounter scenes: " + list.size() + " total (" + wonders + " wonder moments)"), false);
                            return list.size();
                        })
                        .then(Commands.argument("hold", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(SKYRIM_HOLDS, b))
                                .executes(c -> {
                                    String hold = StringArgumentType.getString(c, "hold");
                                    List<Scene> list = forHold(hold);
                                    long wonders = list.stream().filter(Scene::wonder).count();
                                    c.getSource().sendSuccess(() -> Component.literal(
                                            "Hold " + hold + ": " + list.size() + " scenes (" + wonders + " wonder): "
                                                    + list.stream().map(Scene::id).reduce((a, b) -> a + ", " + b).orElse("none")), false);
                                    return list.size();
                                })))
                .then(Commands.literal("roll")
                        .executes(c -> {
                            ServerPlayer sp = c.getSource().getPlayerOrException();
                            Scene s = rollNearPlayer(sp, null);
                            if (s == null) {
                                c.getSource().sendFailure(Component.literal("No scene matched current location/time."));
                                return 0;
                            }
                            c.getSource().sendSuccess(() -> Component.literal("Rolled scene: " + s.id + (s.wonder ? " [WONDER]" : "")), true);
                            return 1;
                        })
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(all().stream().map(Scene::id).toList(), b))
                                .executes(c -> {
                                    ServerPlayer sp = c.getSource().getPlayerOrException();
                                    String id = StringArgumentType.getString(c, "id");
                                    Scene s = rollNearPlayer(sp, id);
                                    if (s == null) {
                                        c.getSource().sendFailure(Component.literal("Could not spawn scene: " + id));
                                        return 0;
                                    }
                                    c.getSource().sendSuccess(() -> Component.literal("Spawned scene: " + s.id + (s.wonder ? " [WONDER]" : "")), true);
                                    return 1;
                                })))
                .then(Commands.literal("selftest")
                        .executes(c -> {
                            List<Scene> list = all();
                            int problems = 0;
                            if (list.size() < 45) problems++;
                            for (String hold : SKYRIM_HOLDS) {
                                List<Scene> h = forHold(hold);
                                long w = h.stream().filter(Scene::wonder).count();
                                if (h.size() < 8 || w < 3) problems++;
                            }
                            for (Scene s : list) {
                                if (s.purpose.isEmpty() || s.needs.isEmpty() || s.why.isEmpty()) problems++;
                            }
                            int p = problems;
                            c.getSource().sendSuccess(() -> Component.literal(
                                    "Scenes selftest: " + list.size() + " scenes, " + p + " problems"), false);
                            return p == 0 ? 1 : 0;
                        })));
    }

    // ---------------------------------------------------------------- JSON loader

    private static List<Scene> load() {
        List<Scene> out = new ArrayList<>();
        try (InputStream in = WildScenes.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                Skycraft.LOGGER.error("Missing {}", RESOURCE);
                return List.of();
            }
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : arr) {
                Scene s = parseScene(el.getAsJsonObject());
                if (s != null) out.add(s);
            }
        } catch (Exception e) {
            Skycraft.LOGGER.error("Failed to load {}", RESOURCE, e);
        }
        Skycraft.LOGGER.info("Loaded {} wild scenes from {}", out.size(), RESOURCE);
        return Collections.unmodifiableList(out);
    }

    @Nullable
    private static Scene parseScene(JsonObject o) {
        String id = str(o, "id", "");
        int weight = o.has("weight") ? o.get("weight").getAsInt() : 10;
        boolean wonder = o.has("wonder") && o.get("wonder").getAsBoolean();
        String road = str(o, "road", "");
        String time = str(o, "time", "any");
        double minWild = o.has("minWildness") ? o.get("minWildness").getAsDouble() : 0.0;
        Set<String> provinces = strSet(o, "provinces");
        Set<String> regions = strSet(o, "regions");
        Set<String> cultures = strSet(o, "cultures");
        Set<BiomeFamily> families = new HashSet<>();
        if (o.has("families")) {
            for (JsonElement el : o.getAsJsonArray("families")) {
                try {
                    families.add(BiomeFamily.valueOf(el.getAsString().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    Skycraft.LOGGER.warn("Unknown biome family '{}' in scene {}", el.getAsString(), id);
                }
            }
        }
        String purpose = str(o, "purpose", "");
        List<String> needs = strList(o, "needs");
        List<String> why = strList(o, "why");
        String questHook = str(o, "questHook", "");
        List<String> props = strList(o, "props");
        List<Actor> actors = new ArrayList<>();
        if (o.has("actors")) {
            for (JsonElement el : o.getAsJsonArray("actors")) {
                JsonObject a = el.getAsJsonObject();
                NpcRole npc = a.has("npc") ? NpcRole.byId(a.get("npc").getAsString()) : null;
                EntityType<?> entity = null;
                if (a.has("entity")) {
                    Identifier eid = Identifier.parse(a.get("entity").getAsString());
                    if (BuiltInRegistries.ENTITY_TYPE.containsKey(eid)) entity = BuiltInRegistries.ENTITY_TYPE.getValue(eid);
                }
                if (npc == null && entity == null) continue;
                int min = 1, max = 1;
                if (a.has("count")) {
                    JsonArray c = a.getAsJsonArray("count");
                    min = Math.max(1, c.get(0).getAsInt());
                    max = c.size() > 1 ? Math.max(min, c.get(1).getAsInt()) : min;
                }
                int radius = a.has("radius") ? a.get("radius").getAsInt() : 3;
                boolean hostile = a.has("hostile") && a.get("hostile").getAsBoolean();
                String group = str(a, "group", "");
                String pose = str(a, "pose", "");
                int leash = a.has("leash") ? a.get("leash").getAsInt() : 0;
                actors.add(new Actor(npc, entity, min, max, radius, hostile, group, pose, leash));
            }
        }
        Map<String, Integer> minFactionRep = intMap(o, "minFactionRep");
        Map<String, Integer> maxFactionRep = intMap(o, "maxFactionRep");
        String requiredDeed = str(o, "requiredDeed", "");
        Set<String> playerRaces = strSet(o, "playerRaces");
        Set<String> rulingFactions = strSet(o, "rulingFactions");
        return actors.isEmpty() ? null : new Scene(
                id, weight, wonder, road, time, minWild,
                provinces, regions, cultures, families,
                purpose, needs, why, questHook, props, actors,
                minFactionRep, maxFactionRep, requiredDeed, playerRaces, rulingFactions);
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    private static Set<String> strSet(JsonObject o, String k) {
        if (!o.has(k)) return Set.of();
        Set<String> s = new HashSet<>();
        for (JsonElement el : o.getAsJsonArray(k)) s.add(el.getAsString());
        return Collections.unmodifiableSet(s);
    }

    private static List<String> strList(JsonObject o, String k) {
        if (!o.has(k)) return List.of();
        List<String> s = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray(k)) s.add(el.getAsString());
        return Collections.unmodifiableList(s);
    }

    private static Map<String, Integer> intMap(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonObject()) return Map.of();
        Map<String, Integer> m = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(k).entrySet()) {
            m.put(e.getKey(), e.getValue().getAsInt());
        }
        return Collections.unmodifiableMap(m);
    }
}

