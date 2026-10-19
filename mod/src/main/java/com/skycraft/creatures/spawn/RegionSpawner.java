package com.skycraft.creatures.spawn;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.weather.RegionalWeather;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.creatures.CreatureSpawns;
import com.skycraft.creatures.CreaturesConfig;
import com.skycraft.creatures.Leveling;
import com.skycraft.creatures.entity.SkyCreature;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * The open world's leveled spawning ({@link SpawnTables}), a lightweight custom spawner per player:
 * <ul>
 *     <li>every two seconds per player (staggered), one attempt for hostiles and one for wildlife;</li>
 *     <li>a spot 28-72 blocks away, behind or beside the player and out of their line of sight, in a chunk that is
 *     already loaded and ticking (never loads chunks);</li>
 *     <li>never inside settlements (named settlement places, village meeting points, houses);</li>
 *     <li>the group is chosen from the rules matching the spot's region, province and biome family, its region
 *     level ({@link Leveling#regionLevel}), the hour, and whether it is on the surface, in a cave or in water;</li>
 *     <li>bounded: at most {@code hostileBudget} hostile and {@code wildlifeBudget} wild creatures it placed within 96
 *     blocks of the player, and a few of any one kind;</li>
 *     <li>what it placed leaves again once nobody has been within 96 blocks for a minute (no accumulation).</li>
 * </ul>
 * Vanilla's own hostile spawning stays suppressed by {@link CreatureSpawns}.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class RegionSpawner {
    private static final int INTERVAL = 40;
    private static final double MIN_DIST = 28;
    private static final double MAX_DIST = 72;
    private static final double BUDGET_RADIUS = 96;
    private static final int MAX_PER_TYPE = 6;
    private static final String SEEN_KEY = "skycraft_rs_seen";
    private static final String GROUP_KEY = "skycraft_rs_group";

    private RegionSpawner() {}

    @SubscribeEvent
    public static void onReload(AddServerReloadListenersEvent event) {
        event.addListener(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "spawn_tables"), new SpawnTables());
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.level();
        if ((level.getGameTime() + player.getId() * 7L) % INTERVAL != 0) return;
        if (!CreaturesConfig.REGION_SPAWNING.get() || player.isSpectator() || !player.isAlive()) return;
        // Nirn and the realms (the realms' tables match their dimension region, e.g. "minecraft:the_nether")
        if (level.dimension() != Level.OVERWORLD && com.skycraft.realms.Realm.of(level) == null || SpawnTables.rules().isEmpty()) return;
        if (!level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS)) return;

        List<Mob> mine = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(BUDGET_RADIUS),
                m -> m.entityTags().contains(SkyCreature.REGION_SPAWN_TAG));
        int hostile = 0;
        int wild = 0;
        java.util.Set<Long> groups = new java.util.HashSet<>();
        for (Mob m : mine) {
            if (m instanceof Enemy) {
                hostile++;
                groups.add(m.getPersistentData().getLongOr(GROUP_KEY, 0L));
            } else {
                wild++;
            }
        }
        RandomSource random = level.getRandom();
        boolean peaceful = level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL;
        // pacing: one hostile group near a player at a time, a cooldown between events, quiet after (re)spawning
        if (!peaceful && hostile < CreaturesConfig.HOSTILE_BUDGET.get() && groups.size() < CreaturesConfig.MAX_HOSTILE_GROUPS.get()
                && SpawnPacing.hostileReady(player)) {
            attempt(level, player, SpawnTables.Kind.HOSTILE, mine, random);
        }
        if (wild < CreaturesConfig.WILDLIFE_BUDGET.get()) attempt(level, player, SpawnTables.Kind.WILDLIFE, mine, random);
    }

    // ------------------------------------------------------------------ one attempt

    /** Maps a regional {@link WeatherKind} and vanilla rain/thunder flags to {@link SpawnTables.Weather}. */
    public static SpawnTables.Weather toSpawnWeather(WeatherKind regional, boolean vanillaRaining, boolean vanillaThundering) {
        if (regional != null) {
            if (regional.severe() || regional == WeatherKind.STORM) return SpawnTables.Weather.STORM;
            if (regional == WeatherKind.RAIN || regional == WeatherKind.DRIZZLE || regional == WeatherKind.SNOW
                    || regional == WeatherKind.FOG || regional == WeatherKind.ASHFALL || regional == WeatherKind.DUST) {
                return SpawnTables.Weather.RAIN;
            }
        }
        return vanillaThundering ? SpawnTables.Weather.STORM
                : vanillaRaining ? SpawnTables.Weather.RAIN : SpawnTables.Weather.CLEAR;
    }

    private static void attempt(ServerLevel level, ServerPlayer player, SpawnTables.Kind kind, List<Mob> mine, RandomSource random) {
        boolean hostileKind = kind == SpawnTables.Kind.HOSTILE;
        // daylight makes hostile attempts rarer unless a severe regional storm darkens the sky
        WeatherKind playerWeather = RegionalWeather.kindAt(level, player.blockPosition());
        boolean severeAtPlayer = RegionalWeather.isSevere(playerWeather) || playerWeather == WeatherKind.STORM;
        if (hostileKind && !level.isDarkOutside() && !severeAtPlayer && random.nextDouble() > CreaturesConfig.DAY_HOSTILE_FACTOR.get()) return;
        BlockPos column = pickColumn(level, player, random, hostileKind ? CreaturesConfig.MIN_HOSTILE_DISTANCE.get() : MIN_DIST);
        if (column == null) return;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        // underground when the player is: caves get cave spawns
        boolean underground = player.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.getBlockX(), player.getBlockZ()) - 8;
        BlockPos spot = underground ? findCaveSpot(level, column, player.getBlockY(), random) : new BlockPos(column.getX(), surface, column.getZ());
        if (spot == null) return;
        SpawnTables.Placement place = classify(level, spot, surface);
        if (place == SpawnTables.Placement.SURFACE && underground) return;
        if (inSettlement(level, spot)) return;
        WeatherKind spotWeather = RegionalWeather.kindAt(level, spot);
        // During severe surface storms (blizzard, sandstorm, ash storm, blight), most wildlife takes shelter
        if (!hostileKind && place == SpawnTables.Placement.SURFACE && RegionalWeather.isSevere(spotWeather)
                && random.nextFloat() < 0.65f) {
            return;
        }
        boolean roadSpot = false;
        if (hostileKind) {
            if (SpawnPacing.calm(level, spot)) return; // around the world spawn and towns
            // main roads feel safe: hostile attempts there mostly fail
            roadSpot = SpawnPacing.onRoad(level, spot);
            if (roadSpot && random.nextDouble() > CreaturesConfig.ROAD_HOSTILE_FACTOR.get()) return;
            // ...and so does walking along one: quiet stretches (the spot is 40+ blocks away, off the road itself)
            if (SpawnPacing.onRoad(level, player.blockPosition()) && random.nextDouble() > CreaturesConfig.ROAD_PLAYER_FACTOR.get()) return;
        }

        Holder<Biome> biome = level.getBiome(spot);
        BiomeFamily family = BiomeFamily.of(biome, spot, level.getSeaLevel());
        Region region = Regions.at(level, spot);
        int regionLevel = Leveling.regionLevel(level, spot);
        int playerLevel = Math.max(1, com.skycraft.core.SkyData.get(player).getLevel());
        // the start area ramps up gently: weak enemies close to where new characters arrive
        double fromStart = SpawnPacing.distFromStart(level, spot.getX(), spot.getZ());
        if (fromStart < 900) regionLevel = Math.min(regionLevel, 1 + (int) (fromStart / 140));
        boolean nearStart = SpawnPacing.nearStart(level, spot.getX(), spot.getZ());
        if (hostileKind) {
            int maxAllowedLevel = playerLevel + (nearStart ? 1 : roadSpot ? 2 : 4);
            regionLevel = Math.min(regionLevel, maxAllowedLevel);
        }
        boolean lowLevelProtected = nearStart || (hostileKind && playerLevel <= 3);
        boolean night = level.isDarkOutside();
        long dayTime = level.getOverworldClockTime() % 24000L;
        SpawnTables.Weather weather = toSpawnWeather(spotWeather, level.isRaining(), level.isThundering());
        // road and settlement distances are only looked up when a rule asks for them (cached per attempt)
        Boolean roadHere = hostileKind ? Boolean.valueOf(roadSpot) : null;
        java.util.Map<Integer, Boolean> townNear = new java.util.HashMap<>();

        List<SpawnTables.Group> candidates = new ArrayList<>();
        int total = 0;
        for (SpawnTables.Rule rule : SpawnTables.rules()) {
            if (!matches(rule, region, family, biome)) continue;
            for (SpawnTables.Group g : rule.groups()) {
                if (g.kind() != kind || regionLevel < g.minLevel() || regionLevel > g.maxLevel()) continue;
                if (!timeOk(g.time(), night, dayTime)) continue;
                if (g.place() != SpawnTables.Placement.ANY && g.place() != place) continue;
                SpawnTables.Where w = g.where();
                if (spot.getY() < w.minY() || spot.getY() > w.maxY()) continue;
                if (w.weather() != SpawnTables.Weather.ANY && w.weather() != weather
                        && !(w.weather() == SpawnTables.Weather.RAIN && weather == SpawnTables.Weather.STORM)) continue;
                if (w.road() != SpawnTables.RoadRule.ANY) {
                    if (roadHere == null) roadHere = SpawnPacing.onRoad(level, spot);
                    if (roadHere != (w.road() == SpawnTables.RoadRule.ON)) continue;
                }
                if (!w.nearPlaces().isEmpty() && !nearPlace(w, spot)) continue;
                if (w.townMin() > 0 && townNear.computeIfAbsent(w.townMin(), r -> SpawnPacing.settlementWithin(level, spot, r))) continue;
                if (w.townMax() > 0 && !townNear.computeIfAbsent(w.townMax(), r -> SpawnPacing.settlementWithin(level, spot, r))) continue;
                if (lowLevelProtected && hasBanned(g)) continue; // no bosses, trolls, sabre cats, bears or bandits near the start / at level 1-3
                candidates.add(g);
                total += g.weight();
            }
        }
        if (total <= 0) return;
        int r = random.nextInt(total);
        SpawnTables.Group chosen = candidates.get(0);
        for (SpawnTables.Group g : candidates) {
            r -= g.weight();
            if (r < 0) {
                chosen = g;
                break;
            }
        }
        // bandits no longer charge out of the woods: on the surface a bandit group becomes the scripted ambush
        // (a demand, a ten-second clock and a choice), rare, and only where the pacing rules allow a hostile event
        if (hostileKind && place == SpawnTables.Placement.SURFACE && hasBandit(chosen)) {
            if (random.nextInt(3) == 0) com.skycraft.society.Ambush.tryStart(player);
            return;
        }
        if (spawnGroup(level, spot, chosen, mine, random) && hostileKind) SpawnPacing.noteHostile(player);
    }

    /** Whether one of the group's place types lies within its reach (barrows and ruins for the dead). */
    private static boolean nearPlace(SpawnTables.Where w, BlockPos spot) {
        for (com.skycraft.tamriel.PlaceType t : w.nearPlaces()) {
            if (Places.nearest(t, spot.getX(), spot.getZ(), w.nearDist()).isPresent()) return true;
        }
        return false;
    }

    /** DAWN is the first ~2500 ticks of daylight, DUSK the last stretch before dark. */
    private static boolean timeOk(SpawnTables.Time t, boolean night, long dayTime) {
        return switch (t) {
            case ANY -> true;
            case DAY -> !night;
            case NIGHT -> night;
            case DAWN -> !night && (dayTime < 2500 || dayTime > 22800);
            case DUSK -> !night && dayTime > 10500 && dayTime < 13000;
        };
    }

    private static boolean hasBandit(SpawnTables.Group g) {
        for (SpawnTables.Member m : g.members()) {
            net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(m.type());
            if (id != null && Skycraft.MODID.equals(id.getNamespace()) && (id.getPath().equals("bandit") || id.getPath().equals("bandit_chief"))) return true;
        }
        return false;
    }

    private static boolean hasBanned(SpawnTables.Group g) {
        for (SpawnTables.Member m : g.members()) {
            net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(m.type());
            if (id != null && Skycraft.MODID.equals(id.getNamespace()) && SpawnPacing.bannedNearStart(id.getPath())) return true;
        }
        return false;
    }

    private static boolean matches(SpawnTables.Rule rule, Region region, BiomeFamily family, Holder<Biome> biome) {
        if (!rule.regions().isEmpty() && !rule.regions().contains(region.id())) return false;
        if (!rule.provinces().isEmpty() && !rule.provinces().contains(region.province())) return false;
        if (!rule.families().isEmpty() && !rule.families().contains(family)) return false;
        if (!rule.biomeTags().isEmpty() || !rule.biomeIds().isEmpty()) {
            boolean any = biome.unwrapKey().map(k -> rule.biomeIds().contains(k.identifier())).orElse(false);
            if (!any) {
                for (TagKey<Biome> tag : rule.biomeTags()) {
                    if (biome.is(tag)) {
                        any = true;
                        break;
                    }
                }
            }
            if (!any) return false;
        }
        return true;
    }

    /** A loaded, entity-ticking column 28-72 blocks away, outside the player's view cone. */
    private static BlockPos pickColumn(ServerLevel level, ServerPlayer player, RandomSource random, double minDist) {
        Vec3 look = player.getViewVector(1f).multiply(1, 0, 1);
        if (look.lengthSqr() < 1.0E-4) look = new Vec3(0, 0, 1);
        look = look.normalize();
        for (int i = 0; i < 4; i++) {
            double ang = random.nextDouble() * Math.PI * 2;
            Vec3 dir = new Vec3(Math.cos(ang), 0, Math.sin(ang));
            if (dir.dot(look) > 0.2) continue; // in front of the player: they would see it pop in
            double dist = minDist + random.nextDouble() * (Math.max(MAX_DIST, minDist + 8) - minDist);
            BlockPos col = BlockPos.containing(player.getX() + dir.x * dist, player.getY(), player.getZ() + dir.z * dist);
            if (!level.isLoaded(col) || !level.isPositionEntityTicking(col)) continue;
            return col;
        }
        return null;
    }

    /** An air pocket with a solid floor near the player's depth (caves, tombs, mines). */
    private static BlockPos findCaveSpot(ServerLevel level, BlockPos column, int playerY, RandomSource random) {
        int start = playerY + random.nextInt(17) - 8;
        for (int dy = 0; dy < 16; dy++) {
            int y = start + (dy % 2 == 0 ? dy / 2 : -(dy / 2 + 1));
            BlockPos p = new BlockPos(column.getX(), y, column.getZ());
            if (y <= level.getMinY() + 2 || y >= (level.getMaxY()+1) - 2) continue;
            if (standable(level, p)) return p;
        }
        return null;
    }

    private static boolean standable(ServerLevel level, BlockPos p) {
        BlockState below = level.getBlockState(p.below());
        return below.isFaceSturdy(level, p.below(), Direction.UP) && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty() && level.getFluidState(p).isEmpty()
                && !below.is(net.minecraft.world.level.block.Blocks.BEDROCK);
    }

    private static SpawnTables.Placement classify(ServerLevel level, BlockPos spot, int surface) {
        if (level.getFluidState(spot.below()).is(FluidTags.WATER) || level.getFluidState(spot).is(FluidTags.WATER)) {
            return SpawnTables.Placement.WATER;
        }
        return spot.getY() < surface - 6 && !level.canSeeSky(spot) ? SpawnTables.Placement.CAVE : SpawnTables.Placement.SURFACE;
    }

    /** Inside a named settlement's footprint, near a village meeting point, or in a house. */
    public static boolean inSettlement(ServerLevel level, BlockPos pos) {
        for (Place p : Places.overlapping(pos.getX() - 24, pos.getZ() - 24, pos.getX() + 24, pos.getZ() + 24)) {
            if (p.type().isSettlement()) return true;
        }
        return CreatureSpawns.isSettlementOrHouse(level, pos);
    }

    // ------------------------------------------------------------------ spawning a group

    private static boolean spawnGroup(ServerLevel level, BlockPos spot, SpawnTables.Group group, List<Mob> mine, RandomSource random) {
        groupId = level.getGameTime() + 1;
        boolean any = false;
        for (SpawnTables.Member m : group.members()) {
            long sameKind = mine.stream().filter(e -> e.getType() == m.type()).count();
            int count = m.min() + (m.max() > m.min() ? random.nextInt(m.max() - m.min() + 1) : 0);
            for (int i = 0; i < count && sameKind < MAX_PER_TYPE; i++) {
                BlockPos at = i == 0 ? spot : near(level, spot, random, group.place());
                if (at == null) continue;
                if (spawnOne(level, m.type(), at, group.place())) {
                    sameKind++;
                    any = true;
                }
            }
        }
        return any;
    }

    /** Id (game time) of the group being placed, stored on each member so groups can be counted. */
    private static long groupId;

    private static BlockPos near(ServerLevel level, BlockPos spot, RandomSource random, SpawnTables.Placement place) {
        for (int i = 0; i < 6; i++) {
            int x = spot.getX() + random.nextInt(7) - 3;
            int z = spot.getZ() + random.nextInt(7) - 3;
            BlockPos col = new BlockPos(x, spot.getY(), z);
            if (!level.isLoaded(col)) continue;
            if (place == SpawnTables.Placement.CAVE) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = col.above(dy);
                    if (standable(level, p)) return p;
                }
            } else {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (Math.abs(y - spot.getY()) <= 3) return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    private static boolean spawnOne(ServerLevel level, EntityType<?> type, BlockPos at, SpawnTables.Placement place) {
        Entity e = type.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (!(e instanceof Mob mob)) {
            if (e != null) e.discard();
            return false;
        }
        if (place == SpawnTables.Placement.WATER
                && (mob instanceof net.minecraft.world.entity.animal.fish.WaterAnimal || mob.canBreatheUnderwater())
                && level.getFluidState(at).isEmpty() && level.getFluidState(at.below()).is(FluidTags.WATER)) {
            at = at.below();
        }
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        if (!level.noCollision(mob) || level.containsAnyLiquid(mob.getBoundingBox()) && place != SpawnTables.Placement.WATER) return false;
        if (place == SpawnTables.Placement.WATER && !mob.canBreatheUnderwater() && !(mob instanceof net.minecraft.world.entity.animal.fish.WaterAnimal)) {
            // land creatures listed for water spots (shore dwellers) need dry ground next to it
            if (!level.getFluidState(at).isEmpty()) return false;
        }
        mob.addTag(SkyCreature.REGION_SPAWN_TAG);
        mob.getPersistentData().putLong(SEEN_KEY, com.skycraft.world.RestManager.liveTime(level));
        mob.getPersistentData().putLong(GROUP_KEY, groupId);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.NATURAL, null);
        level.addFreshEntityWithPassengers(mob);
        return true;
    }

    // ------------------------------------------------------------------ despawning what we placed

    /** Region-spawned creatures leave once nobody has been within 96 blocks for a minute. */
    @SubscribeEvent
    public static void onLivingTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre event) {
        Entity e = event.getEntity();
        if (e.tickCount % 100 != 0 || e.level().isClientSide() || !(e instanceof Mob mob)) return;
        if (!mob.entityTags().contains(SkyCreature.REGION_SPAWN_TAG)) return;
        if (mob.hasCustomName() || mob.isLeashed() || mob.isPersistenceRequired() || mob.isVehicle()) {
            mob.removeTag(SkyCreature.REGION_SPAWN_TAG);
            return;
        }
        long now = com.skycraft.world.RestManager.liveTime(mob.level());
        if (mob.level().getNearestPlayer(mob, BUDGET_RADIUS) != null) {
            mob.getPersistentData().putLong(SEEN_KEY, now);
        } else if (now - mob.getPersistentData().getLongOr(SEEN_KEY, 0L) > 1200) {
            mob.discard();
        }
    }

    /** Line of sight from a player's eyes (used by tests and other spawners). */
    public static boolean visibleFrom(ServerPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition();
        if (eye.distanceToSqr(point) > 96 * 96) return false;
        return player.level().clip(new ClipContext(eye, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }
}
