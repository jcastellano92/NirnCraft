package com.skycraft.landmarks;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Skill;
import com.skycraft.core.SkyData;
import com.skycraft.network.NotifyKind;
import com.skycraft.skills.Progression;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.world.LocationKind;
import com.skycraft.world.WorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

import java.util.Arrays;
import java.util.Locale;

/**
 * Server side of the landmarks: "Found" lines with a little Athletics experience the first time a player comes upon
 * a site (L and XL ones also enter the discovered-locations list, so they show in the journal and on the map), and
 * the residents of scenes (a pilgrim, a hermit and her goats, hunters), spawned on the server thread the first time
 * a player comes near, because NPCs register with the townsfolk registry and so cannot be made during worldgen.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class LandmarkEvents {
    private static final int INTERVAL = 40;
    private static final float[] XP = {1.0f, 2.0f, 4.0f, 8.0f};

    private LandmarkEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isSpectator() || player.level().dimension() != Level.OVERWORLD) return;
        if ((player.tickCount + (player.getId() & 31) + 7) % INTERVAL != 0) return;
        try {
            ServerLevel level = player.level();
            int x = player.getBlockX(), z = player.getBlockZ();
            for (LandmarkSite s : Landmarks.within(level.getSeed(), x, z, 80)) {
                double d = Math.sqrt(s.distSqr(x, z));
                int reach = switch (s.tier()) {
                    case S, M -> s.kind().radius() + 6;
                    case L -> s.kind().radius() + 10;
                    case XL -> 48;
                };
                if (d <= reach) found(player, level, s);
                if (!s.kind().cast().isEmpty() && d <= 40) populate(level, s);
            }
            populatePlanned(level, x, z);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Skycraft landmarks check failed for {}", player.getName().getString(), e);
        }
    }

    // ------------------------------------------------------------------ found

    private static void found(ServerPlayer player, ServerLevel level, LandmarkSite s) {
        PlayerData data = SkyData.get(player);
        CompoundTag mod = data.module("landmarks");
        long[] found = mod.getLongArray("found").orElseGet(() -> new long[0]);
        long key = s.key();
        for (long k : found) if (k == key) return;
        if (!built(level, s)) return;
        long[] grown = Arrays.copyOf(found, found.length + 1);
        grown[found.length] = key;
        mod.putLongArray("found", grown);
        data.addStat("landmarks_found", 1);
        data.markDirty();
        Component what = s.name().isEmpty() ? s.kind().displayName() : Component.literal(s.name());
        if (s.tier() == Tier.L || s.tier() == Tier.XL) {
            addToJournal(data, level, s);
            Notifier.send(player, NotifyKind.LOCATION_DISCOVERED, what, s.kind().displayName());
        } else {
            Notifier.message(player, Component.translatable("landmarks.skycraft.found", what));
        }
        Progression.addSkillXp(player, Skill.ATHLETICS, XP[s.tier().ordinal()]);
    }

    private static void addToJournal(PlayerData data, ServerLevel level, LandmarkSite s) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s.x(), s.z());
        CompoundTag loc = new CompoundTag();
        loc.putString("id", "landmark|" + s.tier().id + "|" + s.cellX() + "," + s.cellZ());
        loc.putString("name", s.name().isEmpty() ? s.kind().id().replace('_', ' ') : s.name());
        loc.putString("type", LocationKind.LANDMARK.id);
        loc.putInt("x", s.x());
        loc.putInt("y", y);
        loc.putInt("z", s.z());
        loc.putString("dim", level.dimension().identifier().toString());
        loc.putInt("ax", s.x());
        loc.putInt("ay", y);
        loc.putInt("az", s.z());
        WorldData.discovered(data).add(loc);
    }

    /** True when the site stands: the buried skull marker is in its chunk. */
    public static boolean built(ServerLevel level, LandmarkSite s) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(s.chunkX(), s.chunkZ());
        if (chunk == null) return false;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof SkullBlockEntity)) continue;
            BlockPos p = be.getBlockPos();
            if (Math.abs(p.getX() - s.x()) <= 1 && Math.abs(p.getZ() - s.z()) <= 1) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ residents

    /**
     * The residents of the planned scenes (the scene plan places the human vignettes, not the landmark grid): a
     * cabin's dweller, a hunter at his stand, an angler on his jetty, a priest at a shrine. Each scene is looked at
     * from its own chunk, once, when a player is within 40 blocks.
     */
    private static void populatePlanned(ServerLevel level, int px, int pz) {
        com.skycraft.worldplan.scene.ScenePlan plan = com.skycraft.worldplan.scene.ScenePlan.get();
        for (int cx = (px - 48) >> 4; cx <= (px + 48) >> 4; cx++) {
            for (int cz = (pz - 48) >> 4; cz <= (pz + 48) >> 4; cz++) {
                for (int s : plan.inChunk(cx, cz)) {
                    if (plan.x[s] >> 4 != cx || plan.z[s] >> 4 != cz) continue;
                    String id = plan.kindOf(s);
                    if (!id.startsWith(com.skycraft.worldplan.scene.ScenePlan.LANDMARK_PREFIX)) continue;
                    Kind kind = Kinds.byId(id.substring(com.skycraft.worldplan.scene.ScenePlan.LANDMARK_PREFIX.length()));
                    if (kind == null || kind.cast().isEmpty()) continue;
                    if (Math.hypot(plan.x[s] - px, plan.z[s] - pz) > 40) continue;
                    LandmarkSite site = new LandmarkSite(kind.tier(), plan.x[s], plan.z[s], plan.x[s], plan.z[s], kind, 0L,
                            plan.facing[s] & 3, "", "", "", "", null);
                    populate(level, site);
                }
            }
        }
    }

    private static final int[][] SPOTS = {{0, 0}, {2, 1}, {-2, 1}, {1, -2}, {-1, 3}, {3, 3}, {-3, -2}, {4, 0}};

    private static void populate(ServerLevel level, LandmarkSite s) {
        LandmarkWorldData world = LandmarkWorldData.get(level);
        if (world.isPopulated(s.key())) return;
        if (!built(level, s)) return;
        world.markPopulated(s.key());
        int i = 0;
        for (String c : s.kind().cast()) {
            int[] o = SPOTS[i++ % SPOTS.length];
            BlockPos spot = standingSpot(level, s.x() + o[0], s.z() + o[1]);
            if (spot == null) continue;
            try {
                spawn(level, spot, c);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.debug("Skycraft landmarks: could not spawn {} at {}", c, spot, e);
            }
        }
    }

    private static void spawn(ServerLevel level, BlockPos spot, String cast) {
        if (cast.startsWith("npc:")) {
            NpcRole role;
            try {
                role = NpcRole.valueOf(cast.substring(4).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return;
            }
            NpcEntity npc = Npcs.create(level, spot, role);
            if (npc == null) return;
            npc.setPersistenceRequired();
            if (level.addFreshEntity(npc)) npc.setHome(spot, 4);
        } else if (cast.startsWith("mob:")) {
            Identifier id = Identifier.tryParse(cast.substring(4));
            EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getValue(id);
            if (type == null) return;
            Entity e = type.create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
            if (!(e instanceof Mob mob)) return;
            mob.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
            mob.setPersistenceRequired();
            level.addFreshEntity(mob);
        }
    }

    /** A floor spot with headroom near (x, z): free air over a sturdy block. */
    private static BlockPos standingSpot(ServerLevel level, int x, int z) {
        if (!level.hasChunkAt(new BlockPos(x, 64, z))) return null;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = top + 1; y > top - 12; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                    && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) {
                return p;
            }
        }
        return null;
    }
}
