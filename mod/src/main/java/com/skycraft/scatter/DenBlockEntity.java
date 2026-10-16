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
import net.minecraft.world.entity.MobSpawnType;
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
    public static final int HOME_RADIUS = 24;
    private static final double ACTIVATION_RANGE = 36.0;
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

        List<Mob> residents = serverLevel.getEntitiesOfClass(Mob.class, new AABB(pos).inflate(32.0),
                m -> m.isAlive() && m.getPersistentData().getLong(DEN_POS_TAG) == pos.asLong());

        if (!be.populated || (residents.isEmpty() && gameTime - be.lastSpawnTick >= RESPAWN_COOLDOWN_TICKS)) {
            if (be.spawnResidents(serverLevel, pos)) {
                be.populated = true;
                be.lastSpawnTick = gameTime;
                be.setChanged();
            }
            return;
        }

        // Re-assert home tether (transient in vanilla Mob) and guide resting creatures back to the den hollow
        boolean isDay = serverLevel.isDay();
        for (Mob mob : residents) {
            if (!mob.hasRestriction()) {
                mob.restrictTo(pos, HOME_RADIUS);
            }
            if (mob.getTarget() == null && (gameTime % 100L == (pos.asLong() & 19L))) {
                boolean nocturnal = mob.getPersistentData().getBoolean(DEN_NOCTURNAL_TAG);
                boolean shouldRest = nocturnal ? isDay : !isDay;
                if (shouldRest && mob.blockPosition().distSqr(pos) > 36.0) {
                    mob.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 0.9);
                }
            }
        }
    }

    private boolean spawnResidents(ServerLevel level, BlockPos pos) {
        RandomSource random = level.getRandom();
        DenChoice choice = chooseRegionalSpecies(pos.getX(), pos.getZ(), random);
        int count = choice.minCount + (choice.maxCount > choice.minCount ? random.nextInt(choice.maxCount - choice.minCount + 1) : 0);
        int spawned = 0;

        for (int i = 0; i < count; i++) {
            Mob mob = choice.type.create(level);
            if (mob == null) continue;

            BlockPos spawnPos = findSafeSpawnPos(level, pos, mob, random, i);
            mob.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, random.nextFloat() * 360.0f, 0.0f);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos), MobSpawnType.STRUCTURE, null, null);
            mob.setPersistenceRequired();
            mob.restrictTo(pos, HOME_RADIUS);
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
        // First resident spawns right at the den hollow/mouth; packmates spawn just outside
        int[][] offsets = {
                {0, 1}, {0, 2}, {-1, 2}, {1, 2}, {0, 3}, {-2, 1}, {2, 1}, {-1, -1}, {1, -1}
        };
        int start = Math.min(index, offsets.length - 1);
        for (int k = 0; k < offsets.length; k++) {
            int[] off = offsets[(start + k) % offsets.length];
            int x = denPos.getX() + off[0];
            int z = denPos.getZ() + off[1];
            for (int dy = 0; dy <= 3; dy++) {
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

    private static DenChoice chooseRegionalSpecies(int x, int z, RandomSource r) {
        Region region = TamrielMap.region(x, z);
        String province = Cultures.province(region, x, z);
        String regId = region.id();
        MapSample s = new MapSample();
        TamrielMap.sample(x, z, s);
        boolean cold = s.elevation > 155 || regId.contains("winterhold") || regId.contains("the_pale")
                || regId.contains("solstheim") || regId.contains("eastmarch") || "atmora".equals(province);
        boolean coast = s.coastDistance >= 0 && s.coastDistance <= 40;
        int roll = r.nextInt(100);

        return switch (province) {
            case "morrowind" -> {
                if (regId.contains("solstheim")) {
                    yield roll < 40 ? new DenChoice(FaunaEntities.BEAR.get(), 1, 2, false, "bear")
                            : roll < 75 ? new DenChoice(Bestiary.WOLF.get(), 2, 3, true, "wolf")
                            : new DenChoice(ModEntities.TROLL.get(), 1, 2, false, "troll");
                }
                yield roll < 35 ? new DenChoice(Bestiary.NIX_HOUND.get(), 2, 3, true, "nix_hound")
                        : roll < 65 ? new DenChoice(Bestiary.KAGOUTI.get(), 1, 2, false, "kagouti")
                        : roll < 85 ? new DenChoice(Bestiary.ALIT.get(), 1, 2, false, "alit")
                        : new DenChoice(Bestiary.CHAURUS.get(), 2, 3, true, "chaurus");
            }
            case "elsweyr", "hammerfell" -> {
                yield roll < 35 ? new DenChoice(Bestiary.SENCHE_TIGER.get(), 1, 2, true, "senche_tiger")
                        : roll < 65 ? new DenChoice(FaunaEntities.SABRE_CAT.get(), 1, 2, true, "sabre_cat")
                        : roll < 85 ? new DenChoice(Bestiary.GIANT_SCORPION.get(), 2, 3, true, "giant_scorpion")
                        : new DenChoice(Bestiary.SAND_SERPENT.get(), 1, 2, false, "sand_serpent");
            }
            case "black_marsh" -> {
                yield roll < 35 ? new DenChoice(Bestiary.WAMASU.get(), 1, 1, false, "wamasu")
                        : roll < 65 ? new DenChoice(ModEntities.TROLL.get(), 1, 2, false, "troll")
                        : roll < 85 ? new DenChoice(Bestiary.HACKWING.get(), 2, 3, false, "hackwing")
                        : new DenChoice(ModEntities.FROSTBITE_SPIDER.get(), 2, 3, true, "giant_spider");
            }
            case "valenwood", "summerset" -> {
                yield roll < 35 ? new DenChoice(ModEntities.SPRIGGAN.get(), 1, 2, false, "spriggan")
                        : roll < 60 ? new DenChoice(Bestiary.INDRIK.get(), 1, 2, false, "indrik")
                        : roll < 85 ? new DenChoice(Bestiary.SENCHE_TIGER.get(), 1, 2, true, "senche_tiger")
                        : new DenChoice(ModEntities.FROSTBITE_SPIDER.get(), 2, 3, true, "giant_spider");
            }
            case "high_rock", "cyrodiil" -> {
                yield roll < 30 ? new DenChoice(FaunaEntities.BEAR.get(), 1, 2, false, "bear")
                        : roll < 55 ? new DenChoice(Bestiary.WOLF.get(), 2, 3, true, "wolf")
                        : roll < 72 ? new DenChoice(Bestiary.DURZOG.get(), 2, 3, true, "durzog")
                        : roll < 86 ? new DenChoice(ModEntities.TROLL.get(), 1, 2, false, "troll")
                        : new DenChoice(ModEntities.SPRIGGAN.get(), 1, 2, false, "spriggan");
            }
            default -> {
                if (coast && cold && roll < 30) {
                    yield new DenChoice(FaunaEntities.HORKER.get(), 2, 3, false, "horker");
                }
                yield roll < 28 ? new DenChoice(FaunaEntities.BEAR.get(), 1, 2, false, "bear")
                        : roll < 56 ? new DenChoice(Bestiary.WOLF.get(), 2, 3, true, "wolf")
                        : roll < 74 ? new DenChoice(FaunaEntities.SABRE_CAT.get(), 1, 2, true, "sabre_cat")
                        : roll < 88 ? new DenChoice(ModEntities.TROLL.get(), 1, 2, false, "troll")
                        : roll < 95 ? new DenChoice(ModEntities.FROSTBITE_SPIDER.get(), 2, 3, true, "frostbite_spider")
                        : new DenChoice(ModEntities.SPRIGGAN.get(), 1, 2, false, "spriggan");
            }
        };
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("Populated", this.populated);
        tag.putLong("LastSpawnTick", this.lastSpawnTick);
        if (!this.denSpecies.isEmpty()) {
            tag.putString("DenSpecies", this.denSpecies);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.populated = tag.getBoolean("Populated");
        this.lastSpawnTick = tag.getLong("LastSpawnTick");
        this.denSpecies = tag.getString("DenSpecies");
    }
}
