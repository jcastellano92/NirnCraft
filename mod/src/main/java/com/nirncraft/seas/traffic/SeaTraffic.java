package com.nirncraft.seas.traffic;

import com.nirncraft.NirnCraft;
import com.nirncraft.sailing.DeckPhysics;
import com.nirncraft.sailing.Ports;
import com.nirncraft.sailing.Route;
import com.nirncraft.sailing.SailingRegistry;
import com.nirncraft.sailing.SeaRoutes;
import com.nirncraft.sailing.ShipDeck;
import com.nirncraft.sailing.ShipEntity;
import com.nirncraft.seas.SeasConfig;
import com.nirncraft.society.Cultures;
import com.nirncraft.society.NpcLook;
import com.nirncraft.society.NpcNames;
import com.nirncraft.society.NpcRole;
import com.nirncraft.society.Npcs;
import com.nirncraft.society.entity.NpcEntity;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Life at sea: ships that sail past players at sea ({@link ShipKind}, steered by an {@link NpcCaptain}). Every
 * {@code interval} seconds per player who is on or by the sea, a ship may appear 110-170 blocks away on open water in a
 * loaded, ticking chunk (never loading one), chosen by the waters: merchants and the navy on the sea lanes, Nord
 * longships in the north, pirates in the Sea of Ghosts, the Abecean, the Iliac and Topal bays, Maormer warships towards
 * Pyandonea, Sload hulks off Thras, and on rare nights a ghost ship. It follows a charted sea route when one passes
 * near, else open water. Capped per player and in all; ships and crews are never saved.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class SeaTraffic {
    private SeaTraffic() {}

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.level();
        int interval = SeasConfig.TRAFFIC_INTERVAL.get() * 20;
        if ((level.getGameTime() + player.getId() * 31L) % interval != 0) return;
        if (!SeasConfig.SEA_TRAFFIC.get() || level.dimension() != Level.OVERWORLD || player.isSpectator() || !player.isAlive()) return;
        if (!atSea(level, player)) return;
        List<ShipEntity> traffic = traffic(level);
        int near = 0;
        for (ShipEntity s : traffic) if (s.distanceToSqr(player) < 256 * 256) near++;
        if (near >= SeasConfig.TRAFFIC_PER_PLAYER.get() || traffic.size() >= SeasConfig.TRAFFIC_MAX.get()) return;
        if (level.getRandom().nextFloat() > 0.6f) return;
        trySpawn(level, player, null);
    }

    /** Ships of the traffic in a level. */
    public static List<ShipEntity> traffic(ServerLevel level) {
        List<ShipEntity> out = new ArrayList<>();
        for (ShipEntity s : DeckPhysics.ships(level)) if (s.autopilot() instanceof NpcCaptain) out.add(s);
        return out;
    }

    private static boolean atSea(ServerLevel level, ServerPlayer player) {
        if (player.getVehicle() instanceof ShipEntity || DeckPhysics.deckShip(player) != null) return true;
        MapSample s = TamrielMap.sampleScratch(player.getBlockX(), player.getBlockZ());
        if (s.coastDistance > 24) return false;
        BlockPos p = player.blockPosition();
        return level.getFluidState(p).is(FluidTags.WATER) || level.getFluidState(p.below()).is(FluidTags.WATER) || s.coastDistance < 0;
    }

    /**
     * Puts a ship on the water near the player: of {@code forced} kind, or one chosen for these waters. Returns the
     * ship, or null when no open water was found.
     */
    @Nullable
    public static ShipEntity trySpawn(ServerLevel level, ServerPlayer player, @Nullable ShipKind forced) {
        RandomSource r = level.getRandom();
        for (int attempt = 0; attempt < 6; attempt++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double dist = forced != null ? 40 + r.nextDouble() * 20 : 110 + r.nextDouble() * 60;
            int x = Mth.floor(player.getX() + Math.cos(ang) * dist), z = Mth.floor(player.getZ() + Math.sin(ang) * dist);
            Float surface = openWater(level, x, z);
            if (surface == null) continue;
            ShipKind kind = forced != null ? forced : choose(level, x, z, r);
            if (kind == null) return null;
            // heading: along a sea route passing near, else across open water away from the nearest coast
            float[][] course = course(level, x, z, r);
            float yaw = (float) Math.toDegrees(Math.atan2(-(course[0][0] - x), course[1][0] - z));
            return spawn(level, kind, x + 0.5, surface, z + 0.5, yaw, course[0], course[1]);
        }
        return null;
    }

    /** Water surface at a loaded, deep, open column (and room for a hull around it), else null. */
    @Nullable
    private static Float openWater(ServerLevel level, int x, int z) {
        BlockPos col = new BlockPos(x, 62, z);
        if (!level.isLoaded(col) || !level.isPositionEntityTicking(col)) return null;
        for (int[] o : new int[][]{{0, 0}, {8, 0}, {-8, 0}, {0, 8}, {0, -8}}) {
            BlockPos p = new BlockPos(x + o[0], 62, z + o[1]);
            if (!level.isLoaded(p)) return null;
            if (!level.getFluidState(p).is(FluidTags.WATER) || !level.getFluidState(p.below(3)).is(FluidTags.WATER)) return null;
            if (!level.getBlockState(p.above(2)).isAir()) return null;
        }
        BlockPos top = new BlockPos(x, 63, z);
        if (!level.getFluidState(top).is(FluidTags.WATER)) return 62f + level.getFluidState(col).getHeight(level, col);
        return 63f + level.getFluidState(top).getHeight(level, top);
    }

    private static ShipKind choose(ServerLevel level, int x, int z, RandomSource r) {
        Region region = TamrielMap.region(x, z);
        String id = region.id(), prov = region.province();
        boolean night = !level.isBrightOutside();
        double ghost = SeasConfig.GHOST_SHIP_CHANCE.get() * (id.equals("sea/sea_of_ghosts") ? 2.5 : 1.0);
        if (night && r.nextDouble() < ghost) return ShipKind.GHOST;
        if (prov.equals("pyandonea") || Math.hypot(x + 17000, z - 19000) < 7000) return r.nextInt(4) > 0 ? ShipKind.MAORMER : ShipKind.PIRATE_LONGSHIP;
        if (prov.equals("thras") || Math.hypot(x + 15000, z - 16500) < 5000) return r.nextInt(3) > 0 ? ShipKind.SLOAD : ShipKind.MAORMER;
        List<ShipKind> pool = new ArrayList<>();
        switch (id) {
            case "sea/sea_of_ghosts" -> add(pool, ShipKind.NORD, 4, ShipKind.MERCHANT, 2, ShipKind.NAVY, 2, ShipKind.PIRATE_LONGSHIP, 3);
            case "sea/abecean_sea", "sea/iliac_bay" -> add(pool, ShipKind.MERCHANT, 4, ShipKind.NAVY, 3, ShipKind.PIRATE_COG, 2, ShipKind.PIRATE_LONGSHIP, 2);
            case "sea/topal_bay" -> add(pool, ShipKind.MERCHANT, 4, ShipKind.NAVY, 2, ShipKind.PIRATE_COG, 2);
            case "sea/inner_sea" -> add(pool, ShipKind.MERCHANT, 5, ShipKind.NAVY, 1);
            case "sea/eltheric_ocean", "sea/padomaic_ocean" -> add(pool, ShipKind.MERCHANT, 2, ShipKind.NAVY, 1, ShipKind.PIRATE_COG, 2, ShipKind.MAORMER, 1);
            default -> {
                if (prov.equals("skyrim") || prov.equals("atmora")) add(pool, ShipKind.NORD, 4, ShipKind.MERCHANT, 2, ShipKind.PIRATE_LONGSHIP, 1);
                else add(pool, ShipKind.MERCHANT, 3, ShipKind.NAVY, 1, ShipKind.PIRATE_LONGSHIP, 1);
            }
        }
        return pool.get(r.nextInt(pool.size()));
    }

    private static void add(List<ShipKind> pool, Object... kindWeight) {
        for (int i = 0; i < kindWeight.length; i += 2) {
            for (int n = 0; n < (Integer) kindWeight[i + 1]; n++) pool.add((ShipKind) kindWeight[i]);
        }
    }

    /** Waypoints: the rest of a charted sea route that passes within 300 blocks, else a long leg over open water. */
    private static float[][] course(ServerLevel level, int x, int z, RandomSource r) {
        if (SeaRoutes.charted()) {
            List<Route> routes = new ArrayList<>(Ports.routes());
            java.util.Collections.shuffle(routes, new java.util.Random(r.nextLong()));
            for (Route route : routes) {
                if (route.expedition()) continue;
                SeaRoutes.Path path = SeaRoutes.path(route, r.nextBoolean() ? route.a() : route.b());
                if (path == null || path.xs().length < 2) continue;
                int best = -1;
                double bestD = 300 * 300;
                for (int i = 0; i < path.xs().length; i++) {
                    double d = (path.xs()[i] - x) * (double) (path.xs()[i] - x) + (path.zs()[i] - z) * (double) (path.zs()[i] - z);
                    if (d < bestD) {
                        bestD = d;
                        best = i;
                    }
                }
                if (best < 0) continue;
                int from = Math.min(best + 1, path.xs().length - 1);
                int n = path.xs().length - from;
                float[] xs = new float[n], zs = new float[n];
                System.arraycopy(path.xs(), from, xs, 0, n);
                System.arraycopy(path.zs(), from, zs, 0, n);
                return new float[][]{xs, zs};
            }
        }
        // open water: the direction that stays farthest from any coast
        double bestAng = r.nextDouble() * Math.PI * 2;
        float bestCoast = Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + r.nextDouble() * 0.3;
            float c = TamrielMap.sampleScratch(x + (int) (Math.cos(a) * 400), z + (int) (Math.sin(a) * 400)).coastDistance;
            if (c < bestCoast) {
                bestCoast = c;
                bestAng = a;
            }
        }
        float[] xs = new float[3], zs = new float[3];
        for (int i = 0; i < 3; i++) {
            xs[i] = (float) (x + Math.cos(bestAng) * 400 * (i + 1));
            zs[i] = (float) (z + Math.sin(bestAng) * 400 * (i + 1));
        }
        return new float[][]{xs, zs};
    }

    /** A crewed ship of the traffic at a position (also used by {@code /nirncraft-seas ship}). */
    @Nullable
    public static ShipEntity spawn(ServerLevel level, ShipKind kind, double x, double surface, double z, float yaw, float[] xs, float[] zs) {
        ShipEntity ship = SailingRegistry.type(kind.type).create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (ship == null) return null;
        ship.snapTo(x, surface - kind.type.draft, z, yaw, 0f);
        ship.initNew(null);
        ship.setLivery(kind.livery);
        ship.setTransient(true);
        ship.setCustomName(Component.translatable("seas.nirncraft.ship." + kind.id));
        if (kind.type.hasSail()) ship.setSail(ShipEntity.SAIL_FULL);
        NpcCaptain captain = new NpcCaptain(kind, xs, zs, 0);
        ship.setAutopilot(captain);
        fillCargo(level, ship, kind);
        if (!level.addFreshEntity(ship)) return null;
        ship.syncCarryPose();
        crew(level, ship, kind, captain);
        return ship;
    }

    private static void fillCargo(ServerLevel level, ShipEntity ship, ShipKind kind) {
        LootTable table = level.getServer().reloadableRegistries().getLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, kind.cargoTable())));
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, ship.position()).create(LootContextParamSets.CHEST);
        table.fill(ship.cargo(), params, level.getRandom().nextLong());
    }

    /** Puts the crew on deck: standing places spread over the main deck. */
    private static void crew(ServerLevel level, ShipEntity ship, ShipKind kind, NpcCaptain captain) {
        ShipDeck.Deck deck = ShipDeck.of(kind.type);
        RandomSource r = level.getRandom();
        int placed = 0;
        for (ShipKind.Crew c : kind.crew) {
            for (int i = 0; i < c.count(); i++) {
                Vec3 spot = null;
                for (int tries = 0; tries < 12 && spot == null; tries++) {
                    double lx = Mth.lerp(r.nextDouble(), deck.minX + 0.9, deck.maxX - 0.9);
                    double lz = Mth.lerp(r.nextDouble(), deck.minZ + 2.0, deck.maxZ - 2.0);
                    float f = deck.floorAt(lx, lz, deck.mainDeck + 0.1);
                    if (!Float.isNaN(f) && Math.abs(f - deck.mainDeck) < 0.05f) spot = ship.toWorld(lx, f + 0.02, lz);
                }
                if (spot == null) spot = ship.toWorld(0, deck.mainDeck + 0.02, -1 + placed * 0.8);
                Mob mob = c.role().isEmpty() ? creature(level, c.entity(), spot) : npc(level, kind, c.role(), spot);
                if (mob == null) continue;
                mob.setCustomName(Component.translatable(c.name()));
                mob.setYRot(ship.getYRot() + r.nextFloat() * 180 - 90);
                CrewGoals.crew(mob);
                mob.getPersistentData().store("nirncraft_seas_ship", net.minecraft.core.UUIDUtil.CODEC,  ship.getUUID());
                if (level.addFreshEntity(mob)) {
                    captain.addCrew(mob);
                    placed++;
                }
            }
        }
    }

    @Nullable
    private static Mob creature(ServerLevel level, String id, Vec3 at) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
        Entity e = type == null ? null : type.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (!(e instanceof Mob mob)) return null;
        mob.snapTo(at.x, at.y, at.z, 0, 0);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(BlockPos.containing(at)), EntitySpawnReason.EVENT, null);
        return mob;
    }

    @Nullable
    private static Mob npc(ServerLevel level, ShipKind kind, String roleId, Vec3 at) {
        NpcRole role = NpcRole.byId(roleId);
        if (role == null) return null;
        NpcEntity npc = Npcs.create(level, BlockPos.containing(at), role);
        if (npc == null) return null;
        npc.snapTo(at.x, at.y, at.z, 0, 0);
        NpcNames.Race race = switch (kind) {
            case MAORMER -> NpcNames.Race.MAORMER;
            case NORD -> NpcNames.Race.NORD;
            case NAVY -> level.getRandom().nextInt(3) == 0 ? NpcNames.Race.BRETON : NpcNames.Race.IMPERIAL;
            default -> null;
        };
        if (race != null) {
            npc.setLook(NpcLook.random(race, npc.isFemale(), false, Cultures.outfitFor(role, race, level.getRandom()), level.getRandom()));
        }
        return npc;
    }

    // ------------------------------------------------------------------ crews

    /** Crews are never kept: one loaded from disk (its ship was not saved) is gone at once. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !event.loadedFromDisk()) return;
        if (event.getEntity().getPersistentData().getBooleanOr(CrewGoals.CREW_TAG, false)) event.setCanceled(true);
    }

    /** Hitting a peaceful ship's crew turns the whole crew against you. */
    @SubscribeEvent
    public static void onHurt(com.nirncraft.combat.BeforeArmorDamageEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        if (!event.getEntity().getPersistentData().getBooleanOr(CrewGoals.CREW_TAG, false)) return;
        if (!(event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker)) return;
        if (!event.getEntity().getPersistentData().read("nirncraft_seas_ship", net.minecraft.core.UUIDUtil.CODEC).isPresent()) return;
        Entity ship = level.getEntity(event.getEntity().getPersistentData().read("nirncraft_seas_ship", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
        if (ship instanceof ShipEntity s && s.autopilot() instanceof NpcCaptain c) c.provoked(level, s, attacker);
    }
}
