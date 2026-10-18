package com.skycraft.scatter;

import com.skycraft.creatures.Bestiary;
import com.skycraft.creatures.ModEntities;
import com.skycraft.dungeons.world.Cultures;
import com.skycraft.fauna.FaunaEntities;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.biome.BiomeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Living monster & animal den controller attached to {@link DenBlock}.
 *
 * <ul>
 *   <li>When a non-spectator player approaches within 36 blocks of an unpopulated den, spawns 1–3 regional den
 *       residents (bears, wolves, sabre cats, trolls, giant spiders, spriggans, kagouti, nix-hounds, senche-tigers,
 *       giant scorpions, wamasu, etc.).</li>
 *   <li>Tethers each resident to the den via {@link Mob#restrictTo(BlockPos, int)} ({@code radius = 24}) and persists
 *       the home link across chunk reloads.</li>
 *   <li>Enforces diurnal/nocturnal rest cycles: nocturnal hunters (wolves, nix-hounds, senche-tigers, spiders) return
 *       to the den hollow by day when out of combat, while diurnal predators (bears, trolls, spriggans, kagouti)
 *       retreat to the den at night.</li>
 * </ul>
 */
public class DenBlockEntity extends BlockEntity {
    public static final String DEN_POS_TAG = "SkycraftDenPos";
    public static final String DEN_NOCTURNAL_TAG = "SkycraftNocturnalDen";
    public static final int HOME_RADIUS = 48;
    private static final double ACTIVATION_RANGE = 32.0;
    private static final long RESPAWN_COOLDOWN_TICKS = 72000L; // 3 in-game days

    private boolean populated;
    private long lastSpawnTick;
    private String denSpecies = "";

    public DenBlockEntity(BlockPos pos, BlockState state) {
        super(BiomeBlocks.CREATURE_DEN_BE.get(), pos, state);
    }

    public boolean isPopulated() {
        return populated;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, DenBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        long gameTime = serverLevel.getGameTime();
        if (((gameTime + (pos.asLong() & 19L)) % 20L) != 0L) return;

        Player nearest = serverLevel.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, ACTIVATION_RANGE,
                p -> p instanceof Player pl && !pl.isSpectator());
        if (nearest == null) return;

        List<Mob> residents = serverLevel.getEntitiesOfClass(Mob.class, new AABB(pos).inflate(56.0),
                m -> m.isAlive() && m.getPersistentData().getLongOr(DEN_POS_TAG, 0L) == pos.asLong());

        if (!be.populated || (residents.isEmpty() && gameTime - be.lastSpawnTick >= RESPAWN_COOLDOWN_TICKS)) {
            // Never activate a den that ended up near a settlement calm zone or traveled road
            if (com.skycraft.creatures.spawn.SpawnPacing.calm(serverLevel, pos)
                    || com.skycraft.roads.RoadCells.isRoad(serverLevel, pos.getX(), pos.getZ(), 56)) {
                be.populated = true;
                be.lastSpawnTick = gameTime;
                be.setChanged();
                return;
            }
            if (be.spawnResidents(serverLevel, pos, nearest)) {
                be.populated = true;
                be.lastSpawnTick = gameTime;
                be.setChanged();
            }
            return;
        }

        // Release home restriction during active combat so predators never drop chase mid-fight;
        // re-assert home tether and diurnal/nocturnal rest cycle when out of combat.
        boolean isDay = serverLevel.isBrightOutside();
        for (Mob mob : residents) {
            if (mob.getTarget() != null && mob.getTarget().isAlive()) {
                if (mob.hasHome()) {
                    mob.clearHome();
                }
                continue;
            }
            if (!mob.hasHome()) {
                mob.setHomeTo(pos, HOME_RADIUS);
            }
            if ((gameTime % 100L == (pos.asLong() & 19L))) {
                boolean nocturnal = mob.getPersistentData().getBooleanOr(DEN_NOCTURNAL_TAG, false);
                boolean shouldRest = nocturnal ? isDay : !isDay;
                if (shouldRest && mob.blockPosition().distSqr(pos) > 36.0) {
                    mob.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0.9);
                }
            }
        }
    }

    private boolean spawnResidents(ServerLevel level, BlockPos pos, Player nearest) {
        RandomSource random = level.getRandom();
        int playerLevel = nearest != null ? Math.max(1, com.skycraft.core.SkyData.get(nearest).getLevel()) : 1;
        boolean starterZone = com.skycraft.creatures.spawn.SpawnPacing.nearStart(level, pos.getX(), pos.getZ())
                || com.skycraft.creatures.spawn.SpawnPacing.distFromStart(level, pos.getX(), pos.getZ()) < 480.0;
        DenChoice choice = chooseRegionalSpecies(pos.getX(), pos.getZ(), playerLevel, starterZone, random);
        int count = choice.minCount + (choice.maxCount > choice.minCount ? random.nextInt(choice.maxCount - choice.minCount + 1) : 0);
        int spawned = 0;

        for (int i = 0; i < count; i++) {
            Mob mob = choice.type.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
            if (mob == null) continue;

            BlockPos spawnPos = findSafeSpawnPos(level, pos, mob, random, i);
            mob.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, random.nextFloat() * 360.0f, 0.0f);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos), EntitySpawnReason.STRUCTURE, null);
            mob.setPersistenceRequired();
            mob.setHomeTo(pos, HOME_RADIUS);
            mob.getPersistentData().putLong(DEN_POS_TAG, pos.asLong());
            mob.getPersistentData().putBoolean(DEN_NOCTURNAL_TAG, choice.nocturnal);

            if (level.addFreshEntity(mob)) {
                spawned++;
            }
        }
        this.denSpecies = choice.label;
        return spawned > 0;
    }

    private static BlockPos findSafeSpawnPos(ServerLevel level, BlockPos denPos, Mob mob, RandomSource random, int index) {
        // First resident spawns inside the excavated den chamber or entrance ramp; packmates spawn just outside
        int[][] offsets = {
                {0, 0}, {0, 1}, {-1, 0}, {1, 0}, {0, 2}, {-1, 2}, {1, 2}, {0, 3}, {-2, 1}, {2, 1}
        };
        int start = Math.min(index, offsets.length - 1);
        for (int k = 0; k < offsets.length; k++) {
            int[] off = offsets[(start + k) % offsets.length];
            int x = denPos.getX() + off[0];
            int z = denPos.getZ() + off[1];
            for (int dy = -2; dy <= 3; dy++) {
                BlockPos candidate = new BlockPos(x, denPos.getY() + dy, z);
                if (level.getBlockState(candidate).isAir() && level.getBlockState(candidate.above()).isAir()
                        && !level.getBlockState(candidate.below()).isAir()) {
                    return candidate;
                }
            }
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(surfaceY - denPos.getY()) <= 6) {
                return new BlockPos(x, surfaceY, z);
            }
        }
        return denPos.above();
    }

    private record DenChoice(EntityType<? extends Mob> type, int minCount, int maxCount, boolean nocturnal, String label) {}

    private static DenChoice chooseRegionalSpecies(int x, int z, int playerLevel, boolean starterZone, RandomSource r) {
        Region region = TamrielMap.region(x, z);
        String province = Cultures.province(region, x, z);
        String regId = region.id();
        MapSample s = new MapSample();
        TamrielMap.sample(x, z, s);
        boolean cold = s.elevation > 155 || regId.contains("winterhold") || regId.contains("the_pale")
                || regId.contains("solstheim") || regId.contains("eastmarch") || "atmora".equals(province);
        boolean coast = s.coastDistance >= 0 && s.coastDistance <= 40;
        int roll = r.nextInt(100);

        // Low-level / starter-corridor dens never spawn pairs of Trolls or Sabre Cats
        if (starterZone || playerLevel <= 4) {
            if (coast && roll < 30) {
                return new DenChoice(FaunaEntities.MUDCRAB.get(), 1, 2, false, "mudcrab");
            }
            if (roll < 55) {
                return new DenChoice(Bestiary.WOLF.get(), 1, 2, true, "wolf");
            }
            if (roll < 80) {
                return new DenChoice(ModEntities.SKEEVER.get(), 2, 3, true, "skeever");
            }
            if (playerLevel >= 3 && roll < 92) {
                return new DenChoice(FaunaEntities.BEAR.get(), 1, 1, false, "bear");
            }
            return new DenChoice(ModEntities.FROSTBITE_SPIDER.get(), 1, 2, true, "frostbite_spider");
        }

        int apexMax = playerLevel >= 12 ? 2 : 1;

        return switch (province) {
            case "morrowind" -> {
                if (regId.contains("solstheim")) {
                    yield roll < 40 ? new DenChoice(FaunaEntities.BEAR.get(), 1, apexMax, false, "bear")
                            : roll < 75 ? new DenChoice(Bestiary.WOLF.get(), 2, 3, true, "wolf")
                            : new DenChoice(ModEntities.TROLL.get(), 1, apexMax, false, "troll");
                }
                yield roll < 35 ? new DenChoice(Bestiary.NIX_HOUND.get(), 2, 3, true, "nix_hound")
                        : roll < 65 ? new DenChoice(Bestiary.KAGOUTI.get(), 1, apexMax, false, "kagouti")
                        : roll < 85 ? new DenChoice(Bestiary.ALIT.get(), 1, 2, false, "alit")
                        : new DenChoice(Bestiary.CHAURUS.get(), 1, 2, true, "chaurus");
            }
            case "elsweyr", "hammerfell" -> {
                yield roll < 35 ? new DenChoice(Bestiary.SENCHE_TIGER.get(), 1, apexMax, true, "senche_tiger")
                        : roll < 65 ? new DenChoice(FaunaEntities.SABRE_CAT.get(), 1, apexMax, true, "sabre_cat")
                        : roll < 85 ? new DenChoice(Bestiary.GIANT_SCORPION.get(), 1, 2, true, "giant_scorpion")
                        : new DenChoice(Bestiary.SAND_SERPENT.get(), 1, apexMax, false, "sand_serpent");
            }
            case "black_marsh" -> {
                yield roll < 35 ? new DenChoice(Bestiary.WAMASU.get(), 1, 1, false, "wamasu")
                        : roll < 65 ? new DenChoice(ModEntities.TROLL.get(), 1, apexMax, false, "troll")
                        : roll < 85 ? new DenChoice(Bestiary.HACKWING.get(), 2, 3, false, "hackwing")
                        : new DenChoice(ModEntities.FROSTBITE_SPIDER.get(), 1, 2, true, "giant_spider");
            }
            case "valenwood", "summerset" -> {
                yield roll < 35 ? new DenChoice(ModEntities.SPRIGGAN.get(), 1, apexMax, false, "spriggan")
                        : roll < 60 ? new DenChoice(Bestiary.INDRIK.get(), 1, 2, false, "indrik")
                        : roll < 85 ? new DenChoice(Bestiary.SENCHE_TIGER.get(), 1, apexMax, true, "senche_tiger")
                        : new DenChoice(ModEntities.FROSTBITE_SPIDER.get(), 1, 2, true, "giant_spider");
            }
            case "high_rock", "cyrodiil" -> {
                yield roll < 30 ? new DenChoice(FaunaEntities.BEAR.get(), 1, apexMax, false, "bear")
                        : roll < 55 ? new DenChoice(Bestiary.WOLF.get(), 2, 3, true, "wolf")
                        : roll < 72 ? new DenChoice(Bestiary.DURZOG.get(), 2, 3, true, "durzog")
                        : roll < 86 ? new DenChoice(ModEntities.TROLL.get(), 1, apexMax, false, "troll")
                        : new DenChoice(ModEntities.SPRIGGAN.get(), 1, 1, false, "spriggan");
            }
            default -> {
                if (coast && cold && roll < 30) {
                    yield new DenChoice(FaunaEntities.HORKER.get(), 2, 3, false, "horker");
                }
                yield roll < 30 ? new DenChoice(FaunaEntities.BEAR.get(), 1, apexMax, false, "bear")
                        : roll < 60 ? new DenChoice(Bestiary.WOLF.get(), 2, 3, true, "wolf")
                        : roll < 76 ? new DenChoice(FaunaEntities.SABRE_CAT.get(), 1, apexMax, true, "sabre_cat")
                        : roll < 88 ? new DenChoice(ModEntities.TROLL.get(), 1, apexMax, false, "troll")
                        : roll < 95 ? new DenChoice(ModEntities.FROSTBITE_SPIDER.get(), 1, 2, true, "frostbite_spider")
                        : new DenChoice(ModEntities.SPRIGGAN.get(), 1, 1, false, "spriggan");
            }
        };
    }

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput tag) {
        super.saveAdditional(tag);
        tag.putBoolean("Populated", this.populated);
        tag.putLong("LastSpawnTick", this.lastSpawnTick);
        if (!this.denSpecies.isEmpty()) {
            tag.putString("DenSpecies", this.denSpecies);
        }
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput tag) {
        super.loadAdditional(tag);
        this.populated = tag.getBooleanOr("Populated", false);
        this.lastSpawnTick = tag.getLongOr("LastSpawnTick", 0L);
        this.denSpecies = tag.getStringOr("DenSpecies", "");
    }
}
