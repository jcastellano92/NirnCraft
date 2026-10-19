package com.nirncraft.creatures.dragons;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.SkyData;
import com.nirncraft.creatures.entity.DragonEntity;
import com.nirncraft.quest.MainQuest;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Optional;

/**
 * Where Skyrim's dragons live, checked every five seconds per player (cheap: a few place lookups, entity scans
 * only near the place, never loading chunks):
 * <ul>
 *     <li><b>Word walls</b>: once the Dragonborn has begun Dragon Rising, a dragon may be perched at a word wall when
 *     a player first comes near; it wakes when someone climbs close. A wall hosts a dragon again a week later.</li>
 *     <li><b>Dragon mounds</b>: after the Way of the Voice, the first time a player comes near an undisturbed burial
 *     mound, Alduin flies over and raises the dragon buried there: bones crawl out of the ground and grow their flesh
 *     back, then the dragon attacks. Each mound rises once.</li>
 *     <li><b>The Throat of the World</b>: Paarthurnax waits on the summit.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class DragonLairs {
    private static final int CHECK_INTERVAL = 100;
    private static final long LAIR_RESPAWN = 24000L * 7;
    /** Fallback summit search centre when the world has no {@code throat_of_the_world} place. */
    private static final int THROAT_X = 579;
    private static final int THROAT_Z = 652;

    private DragonLairs() {}

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.level();
        if ((level.getGameTime() + player.getId() * 13L) % CHECK_INTERVAL != 0) return;
        if (level.dimension() != Level.OVERWORLD || player.isSpectator() || !player.isAlive()) return;
        int stage = MainQuest.stage(SkyData.get(player));
        int x = player.getBlockX();
        int z = player.getBlockZ();
        DragonWorldData data = DragonWorldData.get(level);
        if (stage >= 5 && !player.isCreative()) checkMound(level, player, data, x, z);
        if (stage >= 3 && !player.isCreative()) checkWordWall(level, player, data, x, z);
        checkThroat(level, data, x, z);
    }

    private static boolean dragonNear(ServerLevel level, double x, double z, double radius) {
        return !level.getEntitiesOfClass(DragonEntity.class, new AABB(x - radius, level.getMinY(), z - radius,
                x + radius, (level.getMaxY()+1), z + radius), d -> d.isAlive()).isEmpty();
    }

    private static void checkMound(ServerLevel level, ServerPlayer player, DragonWorldData data, int x, int z) {
        Optional<Place> found = Places.nearest(PlaceType.DRAGON_MOUND, x, z, 72);
        if (found.isEmpty()) return;
        Place mound = found.get();
        if (data.isRaised(mound.id()) || !level.hasChunk(mound.x() >> 4, mound.z() >> 4)) return;
        if (dragonNear(level, mound.x(), mound.z(), 160)) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mound.x(), mound.z());
        BlockPos at = new BlockPos(mound.x(), y, mound.z());
        data.markRaised(mound.id());
        NamedDragons.alduinFlyover(level, Vec3.atBottomCenterOf(at), 360);
        DragonEntity risen = NamedDragons.create(level, at, 0, null);
        if (risen == null) return;
        risen.setRole(DragonEntity.ROLE_WILD);
        risen.setPersistenceRequired();
        risen.startRising();
        level.addFreshEntity(risen);
        level.playSound(null, at, com.nirncraft.audio.AudioSounds.creature("dragon", "roar"), SoundSource.HOSTILE, 10f, 0.8f);
        com.nirncraft.core.Notifier.message(player, Component.translatable("message.nirncraft.dragon.mound_rises"));
    }

    private static void checkWordWall(ServerLevel level, ServerPlayer player, DragonWorldData data, int x, int z) {
        Optional<Place> found = Places.nearest(PlaceType.WORD_WALL, x, z, 110);
        if (found.isEmpty()) return;
        Place wall = found.get();
        long now = level.getGameTime();
        if (now < data.lairReady(wall.id()) || !level.hasChunk(wall.x() >> 4, wall.z() >> 4)) return;
        if (wall.distSqr(x, z) < 48 * 48) return; // only appear out of sight, before the player arrives
        data.setLairReady(wall.id(), now + LAIR_RESPAWN);
        if (dragonNear(level, wall.x(), wall.z(), 200) || level.getRandom().nextFloat() > 0.6f) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wall.x() + 6, wall.z() + 6);
        BlockPos at = new BlockPos(wall.x() + 6, y, wall.z() + 6);
        DragonEntity d = NamedDragons.create(level, at, 0, null);
        if (d == null) return;
        d.setRole(DragonEntity.ROLE_LAIR);
        d.setFlying(false);
        d.setPerch(Vec3.atBottomCenterOf(at));
        level.addFreshEntity(d);
    }

    private static void checkThroat(ServerLevel level, DragonWorldData data, int x, int z) {
        if (data.paarthurnaxDead()) return;
        Place throat = Places.byId("throat_of_the_world").orElse(null);
        int tx = throat != null ? throat.x() : THROAT_X;
        int tz = throat != null ? throat.z() : THROAT_Z;
        long dx = x - tx;
        long dz = z - tz;
        if (dx * dx + dz * dz > 128L * 128L || !level.hasChunk(tx >> 4, tz >> 4)) return;
        if (!level.getEntitiesOfClass(DragonEntity.class, new AABB(tx - 160, level.getMinY(), tz - 160, tx + 160,
                (level.getMaxY()+1), tz + 160), d -> d.getVariant() == DragonEntity.PAARTHURNAX).isEmpty()) return;
        BlockPos summit = NamedDragons.summit(level, tx, tz, throat != null ? Math.min(48, throat.radius()) : 48);
        NamedDragons.spawnPaarthurnax(level, summit);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof DragonEntity d && d.getVariant() == DragonEntity.PAARTHURNAX
                && d.level() instanceof ServerLevel level && !event.isCanceled()) {
            DragonWorldData.get(level).setPaarthurnaxDead();
        }
    }
}
