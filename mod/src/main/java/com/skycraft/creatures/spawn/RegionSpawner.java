package com.skycraft.creatures.spawn;

import com.skycraft.Skycraft;
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
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class RegionSpawner {
    private static final int INTERVAL = 40;
    private static final double MIN_DIST = 28;
    private static final double MAX_DIST = 72;
    private static final double BUDGET_RADIUS = 96;
    private static final int MAX_PER_TYPE = 6;
    private static final String SEEN_KEY = "skycraft_rs_seen";

    private RegionSpawner() {}

    @SubscribeEvent
    public static void onReload(AddReloadListenerEvent event) {
        event.addListener(new SpawnTables());
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if ((level.getGameTime() + player.getId() * 7L) % INTERVAL != 0) return;
        if (!CreaturesConfig.REGION_SPAWNING.get() || player.isSpectator() || !player.isAlive()) return;
        // Nirn and the realms (the realms' tables match their dimension region, e.g. "minecraft:the_nether")
        if (level.dimension() != Level.OVERWORLD && com.skycraft.realms.Realm.of(level) == null || SpawnTables.rules().isEmpty()) return;
        if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING)) return;

        List<Mob> mine = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(BUDGET_RADIUS),
                m -> m.getTags().contains(SkyCreature.REGION_SPAWN_TAG));
        int hostile = 0;
        int wild = 0;
        for (Mob m : mine) {
            if (m instanceof Enemy) hostile++;
            else wild++;
        }
        RandomSource random = level.getRandom();
        boolean peaceful = level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL;
        if (!peaceful && hostile < CreaturesConfig.HOSTILE_BUDGET.get()) attempt(level, player, SpawnTables.Kind.HOSTILE, mine, random);
        if (wild < CreaturesConfig.WILDLIFE_BUDGET.get()) attempt(level, player, SpawnTables.Kind.WILDLIFE, mine, random);
    }

    // ------------------------------------------------------------------ one attempt

    private static void attempt(ServerLevel level, ServerPlayer player, SpawnTables.Kind kind, List<Mob> mine, RandomSource random) {
        BlockPos column = pickColumn(level, player, random);
        if (column == null) return;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        // underground when the player is: caves get cave spawns
        boolean underground = player.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.getBlockX(), player.getBlockZ()) - 8;
        BlockPos spot = underground ? findCaveSpot(level, column, player.getBlockY(), random) : new BlockPos(column.getX(), surface, column.getZ());
        if (spot == null) return;
        SpawnTables.Placement place = classify(level, spot, surface);
        if (place == SpawnTables.Placement.SURFACE && underground) return;
        if (inSettlement(level, spot)) return;

        Holder<Biome> biome = level.getBiome(spot);
        BiomeFamily family = BiomeFamily.of(biome, spot);
        Region region = Regions.at(level, spot);
        int regionLevel = Leveling.regionLevel(level, spot);
        boolean night = level.isNight();

        List<SpawnTables.Group> candidates = new ArrayList<>();
        int total = 0;
        for (SpawnTables.Rule rule : SpawnTables.rules()) {
            if (!matches(rule, region, family, biome)) continue;
            for (SpawnTables.Group g : rule.groups()) {
                if (g.kind() != kind || regionLevel < g.minLevel() || regionLevel > g.maxLevel()) continue;
                if (g.time() == SpawnTables.Time.DAY && night || g.time() == SpawnTables.Time.NIGHT && !night) continue;
                if (g.place() != SpawnTables.Placement.ANY && g.place() != place) continue;
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
        spawnGroup(level, spot, chosen, mine, random);
    }

    private static boolean matches(SpawnTables.Rule rule, Region region, BiomeFamily family, Holder<Biome> biome) {
        if (!rule.regions().isEmpty() && !rule.regions().contains(region.id())) return false;
        if (!rule.provinces().isEmpty() && !rule.provinces().contains(region.province())) return false;
        if (!rule.families().isEmpty() && !rule.families().contains(family)) return false;
        if (!rule.biomeTags().isEmpty() || !rule.biomeIds().isEmpty()) {
            boolean any = biome.unwrapKey().map(k -> rule.biomeIds().contains(k.location())).orElse(false);
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
    private static BlockPos pickColumn(ServerLevel level, ServerPlayer player, RandomSource random) {
        Vec3 look = player.getViewVector(1f).multiply(1, 0, 1);
        if (look.lengthSqr() < 1.0E-4) look = new Vec3(0, 0, 1);
        look = look.normalize();
        for (int i = 0; i < 4; i++) {
            double ang = random.nextDouble() * Math.PI * 2;
            Vec3 dir = new Vec3(Math.cos(ang), 0, Math.sin(ang));
            if (dir.dot(look) > 0.2) continue; // in front of the player: they would see it pop in
            double dist = MIN_DIST + random.nextDouble() * (MAX_DIST - MIN_DIST);
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
            if (y <= level.getMinBuildHeight() + 2 || y >= level.getMaxBuildHeight() - 2) continue;
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

    private static void spawnGroup(ServerLevel level, BlockPos spot, SpawnTables.Group group, List<Mob> mine, RandomSource random) {
        for (SpawnTables.Member m : group.members()) {
            long sameKind = mine.stream().filter(e -> e.getType() == m.type()).count();
            int count = m.min() + (m.max() > m.min() ? random.nextInt(m.max() - m.min() + 1) : 0);
            for (int i = 0; i < count && sameKind < MAX_PER_TYPE; i++) {
                BlockPos at = i == 0 ? spot : near(level, spot, random, group.place());
                if (at == null) continue;
                if (spawnOne(level, m.type(), at, group.place())) sameKind++;
            }
        }
    }

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
        Entity e = type.create(level);
        if (!(e instanceof Mob mob)) {
            if (e != null) e.discard();
            return false;
        }
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        if (!level.noCollision(mob) || level.containsAnyLiquid(mob.getBoundingBox()) && place != SpawnTables.Placement.WATER) return false;
        if (place == SpawnTables.Placement.WATER && !mob.canBreatheUnderwater() && !(mob instanceof net.minecraft.world.entity.animal.WaterAnimal)) {
            // land creatures listed for water spots (shore dwellers) need dry ground next to it
            if (!level.getFluidState(at).isEmpty()) return false;
        }
        mob.addTag(SkyCreature.REGION_SPAWN_TAG);
        mob.getPersistentData().putLong(SEEN_KEY, level.getGameTime());
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.NATURAL, null, null);
        level.addFreshEntityWithPassengers(mob);
        return true;
    }

    // ------------------------------------------------------------------ despawning what we placed

    /** Region-spawned creatures leave once nobody has been within 96 blocks for a minute. */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        Entity e = event.getEntity();
        if (e.tickCount % 100 != 0 || e.level().isClientSide || !(e instanceof Mob mob)) return;
        if (!mob.getTags().contains(SkyCreature.REGION_SPAWN_TAG)) return;
        if (mob.hasCustomName() || mob.isLeashed() || mob.isPersistenceRequired() || mob.isVehicle()) {
            mob.removeTag(SkyCreature.REGION_SPAWN_TAG);
            return;
        }
        long now = mob.level().getGameTime();
        if (mob.level().getNearestPlayer(mob, BUDGET_RADIUS) != null) {
            mob.getPersistentData().putLong(SEEN_KEY, now);
        } else if (now - mob.getPersistentData().getLong(SEEN_KEY) > 1200) {
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
