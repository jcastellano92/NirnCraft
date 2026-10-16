package com.skycraft.seas;

import com.skycraft.Skycraft;
import com.skycraft.creatures.entity.SkyCreature;
import com.skycraft.sailing.DeckPhysics;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Life in Nirn's waters and over its wilds, around each player (server; a light spawner of its own, alongside the
 * creatures module's region spawner which places surface water life):
 * <ul>
 *     <li>at sea and underwater: dreugh in Morrowind's waters, sea netch drifting in warm seas, slaughterfish and
 *     their giant elders in the cold, whales out on the open ocean, seals in the Sea of Ghosts, schools of Abecean
 *     longfins; the rare sea serpent rising over deep open ocean, and the sea leviathan in the Maormer waters;</li>
 *     <li>in rivers and lakes: schools of river betties;</li>
 *     <li>in the sky: hawks wheeling over tundra, crags and coasts by day.</li>
 * </ul>
 * Spots 24-64 blocks away in loaded, ticking chunks only; bounded per player; what it places is tagged as a region
 * spawn, so it leaves again a minute after everyone has gone.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class SeaLife {
    private static final int INTERVAL = 40;
    private static final String TAG = "skycraft_sea_life";

    private SeaLife() {}

    private record Pick(String id, int weight, int min, int max, boolean bed) {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if ((level.getGameTime() + player.getId() * 13L) % INTERVAL != 0) return;
        if (!SeasConfig.SEA_LIFE.get() || level.dimension() != Level.OVERWORLD || player.isSpectator() || !player.isAlive()) return;
        if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING)) return;
        List<Mob> mine = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(96), m -> m.getTags().contains(TAG));
        RandomSource r = level.getRandom();
        if (mine.size() < SeasConfig.SEA_LIFE_BUDGET.get()) water(level, player, mine, r);
        if (SeasConfig.HAWKS.get() && level.isDay()) sky(level, player, mine, r);
    }

    // ------------------------------------------------------------------ the water

    private static void water(ServerLevel level, ServerPlayer player, List<Mob> mine, RandomSource r) {
        for (int attempt = 0; attempt < 6; attempt++) {
            double ang = r.nextDouble() * Math.PI * 2, dist = 16 + r.nextDouble() * 44;
            int x = Mth.floor(player.getX() + Math.cos(ang) * dist), z = Mth.floor(player.getZ() + Math.sin(ang) * dist);
            BlockPos col = new BlockPos(x, 62, z);
            if (!level.isLoaded(col) || !level.isPositionEntityTicking(col)) continue;
            int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            int depth = surface - floor;
            if (depth < 2 || !level.getFluidState(new BlockPos(x, surface - 1, z)).is(FluidTags.WATER)) continue;
            MapSample s = TamrielMap.sampleScratch(x, z);
            boolean sea = s.coastDistance < 4 && !s.inWater() && surface <= 64;
            List<Pick> picks = sea ? seaPicks(level, x, z, s, depth) : freshPicks(level, x, z, depth, s);
            Pick p = weighted(picks, r);
            if (p == null) return;
            long same = mine.stream().filter(m -> EntityType.getKey(m.getType()).toString().equals(p.id)).count();
            if (same >= 5 && p.max <= 2 || same >= 14) continue;
            int count = p.min + r.nextInt(p.max - p.min + 1);
            for (int i = 0; i < count; i++) {
                int px = x + (i == 0 ? 0 : r.nextInt(5) - 2), pz = z + (i == 0 ? 0 : r.nextInt(5) - 2);
                int pf = level.getHeight(Heightmap.Types.OCEAN_FLOOR, px, pz), ps = level.getHeight(Heightmap.Types.WORLD_SURFACE, px, pz);
                if (ps - pf < 2) continue;
                int y = p.bed ? pf : pf + 1 + r.nextInt(Math.max(1, ps - pf - 1));
                spawn(level, p.id, new BlockPos(px, Math.min(ps - 1, y), pz));
            }
            return;
        }
    }

    private static List<Pick> seaPicks(ServerLevel level, int x, int z, MapSample s, int depth) {
        Region region = TamrielMap.region(x, z);
        String id = region.id(), prov = region.province();
        float temp = level.getBiome(new BlockPos(x, 62, z)).value().getBaseTemperature();
        boolean warm = temp >= 0.75f, cold = temp < 0.2f;
        boolean open = s.coastDistance < -250;
        List<Pick> out = new ArrayList<>();
        if (id.equals("sea/inner_sea") || prov.equals("morrowind") || prov.equals("black_marsh")) out.add(new Pick("skycraft:dreugh", 3, 1, 2, false));
        if (warm) {
            out.add(new Pick("skycraft:sea_netch", 4, 1, 3, false));
            out.add(new Pick("skycraft:abecean_longfin", 6, 4, 8, false));
            out.add(new Pick("minecraft:tropical_fish", 5, 4, 8, false));
            out.add(new Pick("minecraft:pufferfish", 2, 1, 3, false));
            out.add(new Pick("minecraft:dolphin", 2, 1, 3, false));
            out.add(new Pick("skycraft:sea_crab", 2, 1, 2, true));
        } else {
            out.add(new Pick("skycraft:slaughterfish", cold ? 4 : 2, 1, 3, false));
            out.add(new Pick("skycraft:abecean_longfin", 4, 3, 7, false));
            out.add(new Pick("minecraft:cod", 5, 4, 8, false));
            if (cold) {
                out.add(new Pick("minecraft:salmon", 4, 3, 6, false));
            } else {
                out.add(new Pick("minecraft:dolphin", 2, 1, 2, false));
            }
        }
        if (depth >= 5) out.add(new Pick("minecraft:squid", 3, 2, 4, false));
        if (depth >= 8) out.add(new Pick("skycraft:giant_slaughterfish", cold ? 2 : 1, 1, 1, false));
        if (cold) out.add(new Pick("skycraft:seal", 3, 1, 3, false));
        if (open && depth >= 12) out.add(new Pick("skycraft:whale", 2, 1, 2, false));
        if (open && depth >= 15 && level.getRandom().nextDouble() < SeasConfig.SERPENT_CHANCE.get()) {
            out.clear();
            out.add(new Pick("skycraft:sea_serpent", 1, 1, 1, false));
        }
        double pyan = Math.hypot(x + 17000, z - 19000);
        if ((prov.equals("pyandonea") || pyan < 6000) && depth >= 15 && level.getRandom().nextInt(80) == 0) {
            out.clear();
            out.add(new Pick("skycraft:sea_leviathan", 1, 1, 1, false));
        }
        return out;
    }

    private static List<Pick> freshPicks(ServerLevel level, int x, int z, int depth, MapSample s) {
        List<Pick> out = new ArrayList<>();
        out.add(new Pick("skycraft:river_betty", 6, 4, 8, false));
        out.add(new Pick("minecraft:salmon", RiverRun.running(level) ? 9 : 4, 3, 6, false));
        out.add(new Pick("minecraft:cod", 3, 2, 5, false));
        float temp = level.getBiome(new BlockPos(x, 62, z)).value().getBaseTemperature();
        if (temp >= 0.8f) {
            out.add(new Pick("minecraft:tropical_fish", 4, 3, 6, false));
        }
        if (depth >= 3) out.add(new Pick("skycraft:slaughterfish", 1, 1, 2, false));
        return out;
    }

    private static Pick weighted(List<Pick> picks, RandomSource r) {
        int total = 0;
        for (Pick p : picks) total += p.weight;
        if (total <= 0) return null;
        int roll = r.nextInt(total);
        for (Pick p : picks) {
            roll -= p.weight;
            if (roll < 0) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ the sky

    private static void sky(ServerLevel level, ServerPlayer player, List<Mob> mine, RandomSource r) {
        if (r.nextInt(3) != 0) return;
        long hawks = mine.stream().filter(m -> m.getType() == SeasRegistry.HAWK.get()).count();
        if (hawks >= 2) return;
        double ang = r.nextDouble() * Math.PI * 2, dist = 30 + r.nextDouble() * 30;
        int x = Mth.floor(player.getX() + Math.cos(ang) * dist), z = Mth.floor(player.getZ() + Math.sin(ang) * dist);
        BlockPos col = new BlockPos(x, 64, z);
        if (!level.isLoaded(col) || !level.isPositionEntityTicking(col)) return;
        var biome = level.getBiome(col);
        float temp = biome.value().getBaseTemperature();
        String path = biome.unwrapKey().map(k -> k.location().getPath()).orElse("");
        boolean open = path.contains("tundra") || path.contains("plains") || path.contains("crag") || path.contains("coast")
                || path.contains("shore") || path.contains("meadow") || path.contains("highland") || path.contains("steppe")
                || path.contains("peak") || path.contains("savanna") || path.contains("moor");
        if (!open || temp > 1.2f) return;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        spawn(level, "skycraft:hawk", new BlockPos(x, ground + 18 + r.nextInt(14), z));
    }

    private static void spawn(ServerLevel level, String id, BlockPos at) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
        if (type == null) return;
        Entity e = type.create(level);
        if (!(e instanceof Mob mob)) {
            if (e != null) e.discard();
            return;
        }
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        if (!level.noCollision(mob)) return;
        mob.addTag(TAG);
        mob.addTag(SkyCreature.REGION_SPAWN_TAG);
        mob.getPersistentData().putLong("skycraft_rs_seen", com.skycraft.world.RestManager.liveTime(level));
        net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn(mob, level, level.getCurrentDifficultyAt(at), MobSpawnType.NATURAL, null, null);
        if (mob.isSpawnCancelled()) return;
        level.addFreshEntity(mob);
    }

    /** True while the player stands on a ship (used by the client ambience too, via the deck reports). */
    static boolean aboard(ServerPlayer p) {
        return DeckPhysics.deckShip(p) != null;
    }
}
