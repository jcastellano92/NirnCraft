package com.skycraft.atmosphere;

import com.skycraft.Skycraft;
import com.skycraft.dungeons.world.CaveRoster;
import com.skycraft.dungeons.world.DungeonPiece;
import com.skycraft.dungeons.world.Theme;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Atmosphere follows actual saved dungeon pieces, never the darkness under an arbitrary roof.
 * Chunk lifecycle supplies the index; player updates only look up metadata, without loading chunks. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class DungeonAir {
    public record Volume(int x0, int y0, int z0, int x1, int y1, int z1, int floor, int style) {
        public double distanceSquared(double x, double y, double z) {
            double dx = Math.max(x0 - x, Math.max(0, x - x1));
            double dy = Math.max(y0 - y, Math.max(0, y - y1));
            double dz = Math.max(z0 - z, Math.max(0, z - z1));
            return dx*dx + dy*dy + dz*dz;
        }
    }
    private static final Map<ServerLevel, Map<Long, List<Volume>>> INDEX = new ConcurrentHashMap<>();
    private record Sent(int hash, int chunkX, int chunkZ) {}
    private static final Map<UUID, Sent> SENT = new ConcurrentHashMap<>();
    private DungeonAir() {}

    @SubscribeEvent
    public static void loaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        List<Volume> volumes = new ArrayList<>();
        for (var start : chunk.getAllStarts().values()) for (var piece : start.getPieces()) {
            if (!(piece instanceof DungeonPiece dp)) continue;
            boolean blackreach = dp.theme == Theme.BLACKREACH;
            if (!blackreach && (!dp.theme.underground() || dp.kind == DungeonPiece.Kind.SURFACE
                    || dp.kind == DungeonPiece.Kind.PORTAL || dp.kind == DungeonPiece.Kind.SHAFT)) continue;
            int style = blackreach ? 3 : dp.theme == Theme.MINE || dp.theme == Theme.DWEMER ? 0 : 1;
            if (dp.theme == Theme.CAVE && CaveRoster.of(dp.flavor) == CaveRoster.ICE) style = 2;
            if (dp.theme == Theme.CAVE && CaveRoster.of(dp.flavor) == CaveRoster.FALMER) style = 3;
            if (dp.has(DungeonPiece.FLAG_GRAND)) {
                var profile = com.skycraft.dungeons.world.GrandCavern.choose(level, dp).profile();
                String particle = profile.particle.toLowerCase(Locale.ROOT);
                if (particle.contains("snow") || particle.contains("frost")) style = 2;
                else if (particle.contains("spore") || profile.lightColor.equals("cyan")) style = 3;
                else if (particle.contains("ash") || particle.contains("dust")) style = 0;
            }
            var b = dp.getBoundingBox();
            volumes.add(new Volume(b.minX(), b.minY(), b.minZ(), b.maxX()+1, b.maxY()+1, b.maxZ()+1, dp.floor, style));
        }
        if (!volumes.isEmpty())
            INDEX.computeIfAbsent(level, k -> new ConcurrentHashMap<>()).put(chunk.getPos().toLong(), List.copyOf(volumes));
    }

    @SubscribeEvent
    public static void unloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var index = INDEX.get(level);
            if (index != null) index.remove(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void unloadedLevel(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) { INDEX.remove(level); SENT.clear(); }
    }

    @SubscribeEvent
    public static void logout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)
                || (player.tickCount + player.getId()) % 10 != 0) return;
        sync(player, false);
    }

    /** Explicit capture handshake; runs on the server thread and never requests terrain. */
    public static void sync(ServerPlayer player, boolean force) {
        var index = INDEX.get(player.serverLevel());
        List<Volume> nearby = new ArrayList<>();
        if (index != null) {
            int cx = player.chunkPosition().x, cz = player.chunkPosition().z;
            // Structure starts are at most eight chunks from their pieces. Metadata only.
            for (int z = cz-16; z <= cz+16; z++) for (int x = cx-16; x <= cx+16; x++) {
                var list = index.get(net.minecraft.world.level.ChunkPos.asLong(x, z));
                if (list != null) for (var v : list)
                    if (v.distanceSquared(player.getX(), player.getEyeY(), player.getZ()) < 160*160) nearby.add(v);
            }
        }
        nearby = new ArrayList<>(VolumeBlend.mergeAligned(nearby));
        nearby.sort(Comparator.comparingDouble(v -> v.distanceSquared(player.getX(), player.getEyeY(), player.getZ())));
        if (nearby.size() > 32) nearby = new ArrayList<>(nearby.subList(0, 32));
        int hash = Objects.hash(player.level().dimension().location(), nearby);
        var sent = new Sent(hash, player.chunkPosition().x, player.chunkPosition().z);
        if (force || !Objects.equals(SENT.put(player.getUUID(), sent), sent))
            SkyNetwork.sendToPlayer(player, new Sync(player.level().dimension().location().toString(), nearby,
                    sent.chunkX, sent.chunkZ));
    }

    public static void register() {
        SkyNetwork.register(Sync.class, NetworkDirection.PLAY_TO_CLIENT, Sync::encode, Sync::decode, Sync::handle);
    }
    public record Sync(String dimension, List<Volume> volumes, int chunkX, int chunkZ) {
        static void encode(Sync m, FriendlyByteBuf b) {
            b.writeUtf(m.dimension, 128); b.writeInt(m.chunkX); b.writeInt(m.chunkZ); b.writeVarInt(m.volumes.size());
            for (var v : m.volumes) {
                b.writeInt(v.x0); b.writeInt(v.y0); b.writeInt(v.z0);
                b.writeInt(v.x1); b.writeInt(v.y1); b.writeInt(v.z1);
                b.writeInt(v.floor); b.writeByte(v.style);
            }
        }
        static Sync decode(FriendlyByteBuf b) {
            String dimension = b.readUtf(128); int x=b.readInt(), z=b.readInt(), count = b.readVarInt();
            if (count < 0 || count > 32) throw new IllegalArgumentException("Dungeon atmosphere volume count");
            List<Volume> volumes = new ArrayList<>(count);
            for (int i=0; i<count; i++) volumes.add(new Volume(b.readInt(), b.readInt(), b.readInt(),
                    b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readUnsignedByte()));
            return new Sync(dimension, List.copyOf(volumes), x, z);
        }
        static void handle(Sync m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.atmosphere.client.InteriorAtmosphere.receive(m));
            ctx.get().setPacketHandled(true);
        }
    }
}
